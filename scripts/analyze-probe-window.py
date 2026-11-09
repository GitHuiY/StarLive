"""Analyze service-only evidence offline; reject incomplete or historical data.

Limits are for one short regression test, not product release guarantees.
Notification submission and notification visibility are separate observations.
"""

import argparse
import json
from pathlib import Path
import statistics
import sys

LIMIT_MS = 15_000


def analyze(before, after, minimum_window_ms=170_000):
    first, last = before["service"], after["service"]
    for sample in (first, last):
        if sample.get("schema_version") != 2 or sample.get("source") != "service_only":
            raise ValueError("Expected schema 2 service-only evidence")
    if not first.get("run_id") or first["run_id"] != last.get("run_id"):
        raise ValueError("Snapshots belong to different service runs")
    if first.get("probe_version") != last.get("probe_version"):
        raise ValueError("Probe version changed during the observation window")

    start = first["generated_at"]["elapsed"]
    finish = last["generated_at"]["elapsed"]
    if finish < start:
        raise ValueError("Service monotonic time went backwards")
    issues = []
    ages = []
    for record, sample in ((before, first), (after, last)):
        age = record["device_epoch_seconds"] * 1000 - sample["generated_at"]["wall"]
        if age < -1500:
            issues.append("Service snapshot is implausibly in the future")
        ages.append(max(0, age))  # up to 1 s negative is date-command rounding
    try:
        window_ms = (after["host_capture_started_monotonic_ns"] - before["host_capture_finished_monotonic_ns"]) / 1_000_000
    except KeyError:
        window_ms = None
        issues.append("Host monotonic window boundaries are missing")
    if window_ms is not None and window_ms < minimum_window_ms:
        issues.append("Observation window is too short")
    if not first.get("service_running") or not last.get("service_running"):
        issues.append("Service was not running at both boundaries")
    if ages[0] > LIMIT_MS:
        issues.append("Baseline service snapshot is stale")

    entered_before = int(first["tick_enter_count"])
    entered_after = int(last["tick_enter_count"])
    ended_after = int(last["tick_end_count"])
    if entered_after < entered_before or not 0 <= ended_after <= entered_after:
        raise ValueError("Invalid service tick counters")
    if entered_after - ended_after > 1:
        issues.append("Multiple unexplained unfinished ticks")
    by_number = {}
    for sample in (first, last):
        for tick in sample.get("recent_ticks", []):
            if not isinstance(tick.get("entered"), dict):
                issues.append("Tick has no entry timestamp")
                continue
            by_number[int(tick["number"])] = tick
    anchor = max(1, entered_before)
    required_count = max(0, entered_after - anchor + 1)
    if required_count > 1000:
        raise ValueError("Tick ring cannot cover this window")
    required = list(range(anchor, entered_after + 1))
    missing = [number for number in required if number not in by_number]
    if missing:
        issues.append("Tick ring is missing required sequence numbers")
    ticks = [by_number[number] for number in required if number in by_number]
    for tick in ticks:
        entry = tick["entered"]
        if entry["elapsed"] > finish + 20:
            issues.append("Tick entry is after its service snapshot")
        end = tick.get("tick_end")
        if isinstance(end, dict) and not entry["elapsed"] <= end["elapsed"] <= finish + 20:
            issues.append("Invalid tick end timestamp")

    end_elapsed_estimate = finish + ages[1]
    leading_gap = None
    if entered_before == 0:
        leading_gap = ticks[0]["entered"]["elapsed"] - start if ticks else end_elapsed_estimate - start
    elif entered_before not in by_number:
        issues.append("Baseline sequence anchor is missing")
    elif by_number[entered_before]["entered"]["elapsed"] > start + 20:
        issues.append("Baseline anchor is after baseline time")
    intervals = []
    for previous, current in zip(ticks, ticks[1:]):
        if current["number"] != previous["number"] + 1:
            continue
        elapsed_gap = current["entered"]["elapsed"] - previous["entered"]["elapsed"]
        uptime_gap = current["entered"]["uptime"] - previous["entered"]["uptime"]
        if elapsed_gap < 0 or uptime_gap < 0:
            raise ValueError("Tick timestamps went backwards")
        intervals.append({"to_tick": current["number"], "elapsed_gap_ms": elapsed_gap,
                          "uptime_gap_ms": uptime_gap, "estimated_sleep_gap_ms": max(0, elapsed_gap - uptime_gap)})
    in_window = [tick for tick in ticks if tick["entered"]["elapsed"] >= start]
    complete = [tick for tick in ticks if isinstance(tick.get("tick_end"), dict)]
    unfinished = [tick for tick in ticks if not isinstance(tick.get("tick_end"), dict)]
    last_entry_tail = end_elapsed_estimate - ticks[-1]["entered"]["elapsed"] if ticks else end_elapsed_estimate - start
    last_end_tail = end_elapsed_estimate - complete[-1]["tick_end"]["elapsed"] if complete else end_elapsed_estimate - start
    unfinished_age = max((end_elapsed_estimate - tick["entered"]["elapsed"] for tick in unfinished), default=0)
    durations = []
    open_query_age = 0
    for tick in ticks:
        value = tick.get("query_duration_ms")
        if isinstance(value, (int, float)):
            durations.append(value)
        if isinstance(tick.get("query_start"), dict) and not isinstance(tick.get("query_end"), dict):
            open_query_age = max(open_query_age, end_elapsed_estimate - tick["query_start"]["elapsed"])
    maximum_gap = max((item["elapsed_gap_ms"] for item in intervals), default=None)
    enough = window_ms is not None and entered_after - entered_before >= max(2, int(window_ms // LIMIT_MS) - 1)
    if not enough:
        issues.append("Too few entered ticks for the external observation window")

    pending = first.get("last_snooze") or {}
    snooze = last.get("last_snooze") or {}
    planned = pending.get("planned_due_elapsed")
    scheduled = pending.get("scheduled_at") or {}
    initial_submission = pending.get("submission_at") or {}
    submission = snooze.get("submission_at") or {}
    request_matches = bool(pending.get("request_id") and pending["request_id"] == snooze.get("request_id"))
    pending_at_start = bool(planned is not None and planned >= start and not initial_submission.get("submitted")
                            and scheduled.get("elapsed") is not None and 0 <= start - scheduled["elapsed"] <= LIMIT_MS)
    submission_elapsed = submission.get("elapsed")
    submitted_in_window = bool(submission.get("submitted") and submission_elapsed is not None and start <= submission_elapsed <= finish)
    counter_before = first.get("run_snooze_submission_count")
    counter_after = last.get("run_snooze_submission_count")
    snooze_delta = counter_after - counter_before if isinstance(counter_before, int) and isinstance(counter_after, int) else None
    delay = submission_elapsed - planned if submission_elapsed is not None and planned is not None else None
    same_due = snooze.get("planned_due_elapsed") == planned
    sufficient = not issues
    cadence = bool(sufficient and maximum_gap is not None and maximum_gap <= LIMIT_MS and ages[1] <= LIMIT_MS
                   and (leading_gap is None or leading_gap <= LIMIT_MS) and last_entry_tail <= LIMIT_MS
                   and last_end_tail <= LIMIT_MS and unfinished_age <= LIMIT_MS and open_query_age <= LIMIT_MS)
    snooze_pass = bool(sufficient and request_matches and pending_at_start and submitted_in_window and same_due
                       and snooze_delta == 1 and delay is not None and 0 <= delay <= LIMIT_MS)
    return {
        "probe_version": last.get("probe_version"), "schema_version": last["schema_version"],
        "same_service_run": True, "external_observation_window_ms": window_ms,
        "minimum_observation_window_ms": minimum_window_ms, "observation_limit_ms": LIMIT_MS,
        "evidence_issues": issues, "evidence_sufficient": sufficient,
        "baseline_snapshot_age_ms": ages[0], "end_snapshot_age_ms": ages[1],
        "entered_tick_delta": entered_after - entered_before, "end_phase": last.get("phase"),
        "missing_tick_numbers": missing, "entered_ticks_in_window": len(in_window),
        "leading_gap_ms": leading_gap, "tail_since_last_entry_ms": last_entry_tail,
        "tail_since_last_completion_ms": last_end_tail, "unfinished_tick_age_ms": unfinished_age,
        "median_elapsed_interval_ms": statistics.median([item["elapsed_gap_ms"] for item in intervals]) if intervals else None,
        "max_elapsed_interval_ms": maximum_gap,
        "max_awake_interval_ms": max((item["uptime_gap_ms"] for item in intervals), default=None),
        "estimated_sleep_in_intervals_ms": sum(item["estimated_sleep_gap_ms"] for item in intervals),
        "max_completed_query_duration_ms": max(durations, default=None),
        "open_query_age_lower_bound_ms": open_query_age,
        "max_query_duration_or_open_lower_bound_ms": max(durations + [open_query_age]),
        "max_callback_uptime_jitter_ms": max((tick.get("uptime_jitter_ms", 0) for tick in ticks), default=None),
        "snooze_request_matches": request_matches, "snooze_was_fresh_and_pending_at_start": pending_at_start,
        "snooze_submitted_in_window": submitted_in_window, "snooze_submission_count_delta": snooze_delta,
        "snooze_submission_delay_ms": delay, "last_snooze": snooze,
        "cadence_within_observation_limit": cadence,
        "snooze_submission_within_observation_limit": snooze_pass,
        "notification_visibility_requires_user_confirmation": True, "intervals": intervals,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--minimum-window-ms", type=int, default=170000)
    args = parser.parse_args()
    result = analyze(json.loads(args.before.read_text(encoding="utf-8")), json.loads(args.after.read_text(encoding="utf-8")), args.minimum_window_ms)
    serialized = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        if args.output.exists():
            raise ValueError("Output already exists; preserve prior evidence")
        args.output.write_text(serialized, encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"analysis_error": str(error)}), file=sys.stderr)
        sys.exit(1)
