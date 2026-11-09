package io.github.githuiy.starlive.monitor

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Keep only timing, system conditions and outcome metadata. No apps, events or use durations. */
internal class MonitorDiagnostics(context: Context) {
    private val file = MonitorAtomicFile(File(context.filesDir, "monitor-diagnostics-v1.json"), 128 * 1024)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var data = runCatching { file.read() }.getOrNull() ?: initial()
    private var previousTick: MonitorClock? = null
    private var previousInteractive = false
    private var current: JSONObject? = null
    private var lastWriteDuration = 0L
    private var writeFailures = 0L

    private fun initial() = JSONObject().put("schema", 1).put("source", "starlive_monitor")
        .put("sdk", Build.VERSION.SDK_INT).put("recent_ticks", JSONArray())

    fun begin(bootCount: Long, origin: String, clock: MonitorClock) {
        previousTick = null
        current = null
        val boot = data.optJSONObject("last_boot_broadcast")
        val observedBootCount = data.optLong("last_observed_boot_count", -1)
        val snooze = data.optJSONObject("last_snooze")?.takeIf {
            it.optString("outcome") in setOf(MonitorSnoozeOutcome.CANCELLED_REBOOT.name,
                MonitorSnoozeOutcome.PENDING.name)
        }
        data = initial().put("run_id", UUID.randomUUID().toString()).put("boot_count", bootCount)
            .put("origin", origin).put("started_at", clock.json()).put("battery_start", battery())
        if (boot != null) data.put("last_boot_broadcast", boot).put("last_observed_boot_count", observedBootCount)
        if (snooze != null) data.put("last_snooze", snooze)
        persist()
    }

    fun phase(phase: MonitorPhase, enabled: Boolean, usage: Boolean, notifications: Boolean,
        issue: String? = null) {
        if (issue != null || data.optString("phase") != phase.name) {
            data.put("issue", issue ?: JSONObject.NULL)
        }
        data.put("phase", phase.name).put("enabled_intent", enabled).put("usage_access", usage)
            .put("notifications_allowed", notifications)
        persist()
    }

    fun boot(clock: MonitorClock, count: Long) {
        data.put("last_boot_broadcast", clock.json()).put("last_observed_boot_count", count)
        persist()
    }

    fun tickEnter(clock: MonitorClock, power: MonitorPower, expectedUptime: Long, expectedElapsed: Long) {
        val entered = data.optLong("tick_enter_count") + 1
        current = JSONObject().put("index", entered).put("entered_at", clock.json())
            .put("interactive", power.interactive).put("locked", power.locked)
            .put("power_save", power.powerSave).put("idle", power.idle)
            .put("expected_uptime", expectedUptime).put("expected_elapsed", expectedElapsed)
            .put("callback_uptime_jitter", (clock.uptime - expectedUptime).coerceAtLeast(0))
            .put("callback_elapsed_late", (clock.elapsed - expectedElapsed).coerceAtLeast(0))
            .put("phase", "query_pending")
        previousTick?.let { before ->
            val kind = when {
                power.interactive != previousInteractive -> "power_transition"
                power.interactive -> "interactive"
                else -> "non_interactive"
            }
            val elapsed = (clock.elapsed - before.elapsed).coerceAtLeast(0)
            val uptime = (clock.uptime - before.uptime).coerceAtLeast(0)
            current?.put("elapsed_gap", elapsed)?.put("uptime_gap", uptime)
                ?.put("deep_sleep_gap", (elapsed - uptime).coerceAtLeast(0))
            recordMax("max_elapsed_gap", elapsed, clock)
            recordMax("max_uptime_gap", uptime, clock)
            recordMax("${kind}_max_elapsed_gap", elapsed, clock)
            recordMax("${kind}_max_uptime_gap", uptime, clock)
        }
        previousTick = clock
        previousInteractive = power.interactive
        data.put("tick_enter_count", entered).put("last_tick_enter", clock.json())
            .put("current_tick", current)
    }

    fun queryStart(clock: MonitorClock) {
        current?.put("phase", "querying")?.put("query_started_at", clock.json())
        persist()
    }

    fun queryEnd(clock: MonitorClock, millis: Long) {
        current?.put("phase", "processing")?.put("query_ended_at", clock.json())
            ?.put("query_duration_millis", millis)
    }

    fun tickEnd(clock: MonitorClock, quality: MonitorDataQuality, issue: String?) {
        val finished = current ?: return
        finished.put("phase", "ended").put("ended_at", clock.json())
            .put("data_quality", quality.name).put("issue", issue ?: JSONObject.NULL)
        val recent = data.getJSONArray("recent_ticks")
        if (recent.length() >= 32) recent.remove(0)
        recent.put(finished)
        current = null
        data.remove("current_tick")
        data.put("tick_end_count", data.optLong("tick_end_count") + 1)
            .put("last_tick_end", clock.json()).put("battery_latest", battery())
        persist()
    }

    fun snooze(id: String, outcome: MonitorSnoozeOutcome, clock: MonitorClock,
        dueElapsed: Long?, delayMillis: Long?) {
        val previous = data.optJSONObject("last_snooze")
        val item = if (previous?.optString("request_id") == id) previous
        else JSONObject().put("request_id", id)
        item.put("outcome", outcome.name).put("changed_at", clock.json())
            .put("due_elapsed", dueElapsed ?: JSONObject.NULL).put("delay_millis", delayMillis ?: JSONObject.NULL)
        if (outcome == MonitorSnoozeOutcome.PENDING) item.put("scheduled_at", clock.json())
        if (outcome == MonitorSnoozeOutcome.SUBMITTED) item.put("submitted_at", clock.json())
        data.put("last_snooze", item)
        persist()
    }

    fun notificationSubmitted(clock: MonitorClock, reasonCount: Int, snooze: Boolean) {
        data.put("notification_submission_count", data.optLong("notification_submission_count") + 1)
            .put("last_notification_submitted_at", clock.json()).put("last_reason_count", reasonCount)
        if (snooze) data.put("snooze_submission_count", data.optLong("snooze_submission_count") + 1)
        persist()
    }

    fun export(): ByteArray = data.toString(2).toByteArray(Charsets.UTF_8)

    private fun recordMax(name: String, value: Long, clock: MonitorClock) {
        if (value > data.optJSONObject(name)?.optLong("millis").orZero()) {
            data.put(name, JSONObject().put("millis", value).put("ended_at", clock.json()))
        }
    }

    private fun battery(): JSONObject = JSONObject()
        .put("percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: JSONObject.NULL)
        .put("charge_counter_microamp_hours", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 } ?: JSONObject.NULL)

    private fun persist() {
        val start = SystemClock.elapsedRealtime()
        data.put("previous_diagnostic_write_millis", lastWriteDuration).put("diagnostic_write_failures", writeFailures)
        if (runCatching { file.write(data, syncBeforeFinish = false) }.isFailure) writeFailures++
        lastWriteDuration = SystemClock.elapsedRealtime() - start
    }

    private fun Long?.orZero() = this ?: 0L
    private fun MonitorClock.json() = JSONObject().put("wall", wall).put("elapsed", elapsed).put("uptime", uptime)
}
