"""Capture one service-only test snapshot. Never reconnect or retry.

Run only at the edges of a passive test window: reading repeatedly through ADB
can change the conditions being measured. This reads our own debug app only.
"""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        parser.error("label must contain only letters, digits, underscores or hyphens")

    workspace = Path(__file__).resolve().parents[2]
    adb = workspace / "tools" / "platform-tools" / "adb.exe"
    output_dir = args.output_dir or workspace / ".device-debug" / "thread-retest"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / (args.label + ".json")
    if output.exists():
        raise RuntimeError("Output already exists; choose a new label to preserve prior evidence")
    environment = dict(os.environ, ADB_USB_LEGACY="1")

    def run(*command):
        result = subprocess.run(
            [str(adb), "-s", args.device] + list(command),
            capture_output=True,
            timeout=15,
            env=environment,
        )
        if result.returncode:
            detail = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
            raise RuntimeError(detail or "ADB command failed")
        return result.stdout.decode("utf-8", errors="strict")

    started = time.time_ns() // 1_000_000
    started_monotonic = time.monotonic_ns()
    service = json.loads(run("shell", "run-as", "org.starlive.probe", "cat", "files/service-state.json"))
    device_seconds = int(run("shell", "date", "+%s").strip())
    finished = time.time_ns() // 1_000_000
    finished_monotonic = time.monotonic_ns()
    record = {
        "label": args.label,
        "captured_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "host_capture_started_ms": started,
        "host_capture_finished_ms": finished,
        "host_capture_started_monotonic_ns": started_monotonic,
        "host_capture_finished_monotonic_ns": finished_monotonic,
        "device_epoch_seconds": device_seconds,
        "source": "service-state.json",
        "service": service,
    }
    output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"saved": str(output), "capture_duration_ms": finished - started, "source": record["source"]}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"error": str(error), "retry_attempted": False}), file=sys.stderr)
        sys.exit(1)
