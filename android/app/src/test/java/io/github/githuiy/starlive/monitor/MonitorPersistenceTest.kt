package io.github.githuiy.starlive.monitor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.PosixAtomicFileShadow
import io.github.githuiy.starlive.core.monitor.ReminderPolicy.PendingSnooze
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PosixAtomicFileShadow::class])
class MonitorPersistenceTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun reloadKeepsEnableIntentDailyKeysAndMonotonicSnooze() {
        val storage = MonitorStorage(app.filesDir)
        val pending = PendingSnooze("request-1", 1_000, 12, 2_000, 3_000)
        val original = MonitorControl(enabled = true, selected = setOf("example.fixture"),
            scopeStartWall = 1_000, bootCount = 12, sentKeys = setOf("totalDaily:2026-11-09"),
            pending = pending, snoozeOutcome = MonitorSnoozeOutcome.PENDING)
        storage.write(original)
        val reloaded = MonitorStorage(app.filesDir).read()
        assertTrue(reloaded.enabled)
        assertEquals(original.selected, reloaded.selected)
        assertEquals(original.sentKeys, reloaded.sentKeys)
        assertEquals(pending.id, reloaded.pending?.id)
        assertEquals(pending.bootCount, reloaded.pending?.bootCount)
        assertEquals(1_803_000L, reloaded.pending?.dueElapsedMillis)

        storage.write(reloaded.copy(enabled = false, pending = null,
            snoozeOutcome = MonitorSnoozeOutcome.CANCELLED_STOPPED))
        val stopped = MonitorStorage(app.filesDir).read()
        assertFalse(stopped.enabled)
        assertNull(stopped.pending)
        assertEquals(original.sentKeys, stopped.sentKeys)
        assertEquals(MonitorSnoozeOutcome.CANCELLED_STOPPED, stopped.snoozeOutcome)
    }

    @Test fun diagnosticsRetainRunMaximumOutsideRingAndDoNotReadPrivateSelection() {
        MonitorStorage(app.filesDir).write(MonitorControl(selected = setOf("example.private_app"),
            sentKeys = setOf("perAppDaily:2026-11-09:example.private_app")))
        val diagnostics = MonitorDiagnostics(app)
        val first = MonitorClock(10_000, 9_000, 8_000)
        val power = MonitorPower(interactive = true, locked = false, powerSave = false, idle = false)
        diagnostics.begin(12, "user", first)
        for (index in 0..35) {
            val advance = if (index == 0) 0L else 80_000L + 10_000L * (index - 1)
            val now = MonitorClock(first.wall + advance, first.elapsed + advance, first.uptime + advance)
            diagnostics.tickEnter(now, power, now.uptime, now.elapsed)
            diagnostics.queryStart(now)
            diagnostics.queryEnd(now, 1)
            diagnostics.tickEnd(now, MonitorDataQuality.PARTIAL, null)
        }
        val current = MonitorClock(500_000, 499_000, 498_000)
        diagnostics.tickEnter(current, power, current.uptime, current.elapsed)
        diagnostics.queryStart(current)
        val export = diagnostics.export().toString(Charsets.UTF_8)
        val data = JSONObject(export)
        assertEquals(37, data.getInt("tick_enter_count"))
        assertEquals(36, data.getInt("tick_end_count"))
        assertEquals(32, data.getJSONArray("recent_ticks").length())
        assertEquals(5, data.getJSONArray("recent_ticks").getJSONObject(0).getInt("index"))
        assertEquals(80_000L, data.getJSONObject("interactive_max_elapsed_gap").getLong("millis"))
        assertEquals("querying", data.getJSONObject("current_tick").getString("phase"))
        assertNotNull(data.getJSONObject("battery_start"))
        assertFalse(export.contains("example.private_app"))
        assertFalse(export.contains("sent_keys"))
    }

    @Test fun sleepDoesNotLookLikeWallClockChange() {
        val before = MonitorClock(1_000, 100, 100)
        assertTrue(MonitorClock(61_000, 60_100, 200).wallConsistentWith(before))
        assertFalse(MonitorClock(65_000, 60_100, 200).wallConsistentWith(before))
        assertFalse(MonitorClock(1_100, 99, 99).wallConsistentWith(before))
    }
}
