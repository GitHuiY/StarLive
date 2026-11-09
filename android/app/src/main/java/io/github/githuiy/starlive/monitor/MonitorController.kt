package io.github.githuiy.starlive.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import io.github.githuiy.starlive.core.monitor.ReminderPolicy
import io.github.githuiy.starlive.core.monitor.ReminderPolicy.SnoozeStatus
import io.github.githuiy.starlive.core.monitor.UsageEvent
import io.github.githuiy.starlive.core.monitor.UsageEvidence
import io.github.githuiy.starlive.core.monitor.UsageSnapshot
import io.github.githuiy.starlive.core.monitor.UsageTimeline
import io.github.githuiy.starlive.core.ReminderSettings
import io.github.githuiy.starlive.data.readCommittedReminderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Controller, storage, timer and notification policy share one serial worker. */
class MonitorController private constructor(private val application: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Handler(HandlerThread("starlive-screen-use").apply { start() }.looper)
    private val view = MutableStateFlow(MonitorSnapshot())
    val snapshot: StateFlow<MonitorSnapshot> = view.asStateFlow()

    private val stopGeneration = AtomicLong()
    private val promotionTicket = AtomicLong()
    private val source by lazy { AndroidUsageSource(application) }
    private val notices by lazy { MonitorNotifications(application) }
    private val storage by lazy { MonitorStorage(application.filesDir) }
    private val diagnostics by lazy { MonitorDiagnostics(application) }

    // Worker-only lifecycle and policy state. Main only claims one promotion ticket.
    private var state: MonitorControl? = null
    private var service: ScreenUseService? = null
    private var activeGeneration = 0L
    private var serial = 0L
    private var pendingStart = 0L
    private var origin = "user"
    private var apps: List<MonitorApp> = emptyList()
    private var bootWithoutCountHandled = false
    private var lastClock: MonitorClock? = null
    private var forcedClockCheck = false
    private var expectedUptime = 0L
    private var expectedElapsed = 0L
    private val poll = Runnable { perform { tick() } }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)

    fun refresh() = post {
        notices.createChannels()
        apps = source.launcherApps()
        if (service != null) {
            if (!checkPrerequisites()) return@post
            val last = view.value.lastUpdateElapsedMillis
            if (last != null && MonitorClock.now().elapsed - last > STALE_AFTER_MILLIS) {
                transition(MonitorPhase.STALE, "heartbeat_stale")
            } else publish()
        } else if (pendingStart == 0L) {
            transition(waitingPhase())
        } else publish()
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            requestStop()
            return
        }
        val generation = stopGeneration.get()
        post {
            if (generation != stopGeneration.get()) return@post
            val current = control()
            if (!current.enabled) store(current.copy(enabled = true, scopeStartWall = MonitorClock.now().wall))
            start("user", generation)
        }
    }

    fun setSelectedPackages(packages: Set<String>) {
        val requested = packages.toSet()
        post {
            val current = control()
            apps = source.launcherApps()
            val visible = apps.mapTo(HashSet()) { it.packageName }
            val selected = requested.intersect(visible)
            if (selected == current.selected) {
                publish()
                return@post
            }
            val clock = MonitorClock.now()
            val pending = current.pending
            store(current.copy(selected = selected, scopeStartWall = nextScopeStart(clock, current),
                pending = null, alertId = null,
                snoozeOutcome = if (pending == null) current.snoozeOutcome else MonitorSnoozeOutcome.CANCELLED_SCOPE,
                snoozeDelay = if (pending == null) current.snoozeDelay else null))
            notices.cancelReminder()
            if (pending != null) diagnostics.snooze(pending.id, MonitorSnoozeOutcome.CANCELLED_SCOPE,
                clock, pending.dueElapsedMillis, null)
            clearUsage()
            if (service != null && checkPrerequisites()) schedule(0)
            else if (service == null && pendingStart == 0L) transition(waitingPhase())
            publish()
        }
    }

    fun exportDiagnostics(target: Uri, callback: (Boolean) -> Unit) {
        worker.post {
            val result = runCatching {
                val output = application.contentResolver.openOutputStream(target, "wt")
                    ?: error("Diagnostic destination unavailable")
                output.use { it.write(diagnostics.export()) }
            }.isSuccess
            main.post { callback(result) }
        }
    }

    internal fun requestStop(done: () -> Unit = {}) {
        stopGeneration.incrementAndGet()
        promotionTicket.set(0)
        post(done) {
            val current = control()
            suspendMonitoring(MonitorPhase.OFF, MonitorSnoozeOutcome.CANCELLED_STOPPED, "user_stop",
                newEnabled = false, resetScope = current.enabled)
        }
    }

    internal fun requestSnooze(alertId: String?, done: () -> Unit) = post(done) {
        val current = control()
        if (alertId == null || alertId != current.alertId || service?.foreground != true ||
            stopGeneration.get() != activeGeneration || !current.enabled) return@post
        val clock = MonitorClock.now()
        if (current.bootCount < 0 || !checkPrerequisites()) return@post
        val snooze = ReminderPolicy.PendingSnooze(UUID.randomUUID().toString(), current.scopeStartWall,
            current.bootCount, clock.wall, clock.elapsed)
        store(current.copy(pending = snooze, alertId = null, snoozeOutcome = MonitorSnoozeOutcome.PENDING,
            snoozeDelay = null))
        notices.cancelReminder()
        diagnostics.snooze(snooze.id, MonitorSnoozeOutcome.PENDING, clock, snooze.dueElapsedMillis, null)
        publish()
    }

    internal fun restoreAfterBoot(done: () -> Unit) = post(done) {
        val clock = MonitorClock.now()
        val boot = source.bootCount()
        diagnostics.boot(clock, boot)
        if (boot < 0 && !bootWithoutCountHandled) {
            normalizeBoot(control(), boot, clock, true)
            bootWithoutCountHandled = true
        }
        if (control().enabled) start("boot", stopGeneration.get())
        else transition(MonitorPhase.OFF)
    }

    internal fun claimStart(ticket: Long): Boolean = ticket != 0L && promotionTicket.compareAndSet(ticket, 0)

    internal fun attach(attached: ScreenUseService, ticket: Long) = post {
        if (ticket != pendingStart || !control().enabled || activeGeneration != stopGeneration.get()) {
            attached.finish(ticket)
            return@post
        }
        pendingStart = 0
        service = attached
        lastClock = null
        forcedClockCheck = false
        diagnostics.begin(control().bootCount, origin, MonitorClock.now())
        if (checkPrerequisites()) {
            transition(MonitorPhase.STARTING)
            schedule(0)
        }
    }

    internal fun promotionFailed(ticket: Long) = post {
        if (pendingStart != ticket) return@post
        pendingStart = 0
        transition(MonitorPhase.WAITING_SYSTEM, "foreground_promotion_failed")
    }

    internal fun detached(detached: ScreenUseService) = post {
        if (service !== detached) return@post
        service = null
        worker.removeCallbacks(poll)
        transition(waitingPhase(), "service_ended")
    }

    internal fun systemChanged(changed: ScreenUseService, timeChanged: Boolean) = post {
        if (service !== changed || !changed.foreground) return@post
        forcedClockCheck = forcedClockCheck || timeChanged
        schedule(0)
    }

    private fun post(done: () -> Unit = {}, operation: () -> Unit) {
        worker.post {
            try { perform(operation) } finally { done() }
        }
    }

    private fun perform(operation: () -> Unit) {
        try {
            if (state == null) normalizeBoot(storage.read(), source.bootCount(), MonitorClock.now(), false)
            operation()
        } catch (_: Exception) {
            endRuntime()
            state = null
            clearUsage()
            view.value = view.value.copy(initialized = true, phase = MonitorPhase.STORAGE_ERROR)
            diagnostics.phase(MonitorPhase.STORAGE_ERROR, view.value.enabledIntent,
                view.value.usageAccess, view.value.notificationsAllowed, "local_state_unavailable")
        }
    }

    private fun normalizeBoot(current: MonitorControl, boot: Long, clock: MonitorClock, force: Boolean) {
        val changed = force || boot != current.bootCount || (boot < 0 && current.pending != null)
        if (!changed) {
            state = current
            return
        }
        val oldSnooze = current.pending
        val next = current.copy(bootCount = boot, pending = null, alertId = null,
            scopeStartWall = if (current.enabled) (clock.wall - clock.elapsed).coerceAtLeast(0) else current.scopeStartWall,
            snoozeOutcome = if (oldSnooze == null) current.snoozeOutcome else MonitorSnoozeOutcome.CANCELLED_REBOOT,
            snoozeDelay = if (oldSnooze == null) current.snoozeDelay else null)
        // A fresh install has no saved intent and needs no boot write.
        if (!current.enabled && current == MonitorControl()) state = next else store(next)
        if (oldSnooze != null) diagnostics.snooze(oldSnooze.id, MonitorSnoozeOutcome.CANCELLED_REBOOT,
            clock, oldSnooze.dueElapsedMillis, null)
    }

    private fun control(): MonitorControl = checkNotNull(state)

    private fun store(next: MonitorControl) {
        if (next == state) return
        storage.write(next)
        state = next
    }

    private fun start(from: String, generation: Long) {
        notices.createChannels()
        apps = source.launcherApps()
        if (service?.foreground == true) {
            if (generation == stopGeneration.get() && checkPrerequisites()) schedule(0)
            publish()
            return
        }
        if (pendingStart != 0L) {
            publish()
            return
        }
        if (generation != stopGeneration.get() || !checkPrerequisites()) return
        val ticket = ++serial
        activeGeneration = generation
        pendingStart = ticket
        origin = from
        promotionTicket.set(ticket)
        clearUsage()
        view.value = view.value.copy(lastUpdateWallMillis = null, lastUpdateElapsedMillis = null)
        transition(MonitorPhase.STARTING)
        try {
            application.startForegroundService(Intent(application, ScreenUseService::class.java)
                .putExtra(ScreenUseService.START_TICKET, ticket))
        } catch (_: RuntimeException) {
            promotionTicket.compareAndSet(ticket, 0)
            pendingStart = 0
            transition(MonitorPhase.WAITING_SYSTEM, "foreground_start_denied")
        }
        worker.postDelayed({
            if (pendingStart == ticket) perform {
                promotionTicket.compareAndSet(ticket, 0)
                pendingStart = 0
                transition(MonitorPhase.WAITING_SYSTEM, "foreground_start_timeout")
            }
        }, 15_000)
    }

    private fun checkPrerequisites(): Boolean {
        val phase = waitingPhase()
        if (phase == MonitorPhase.WAITING_SYSTEM && !backgroundRestricted()) return true
        val canceled = when (phase) {
            MonitorPhase.WAITING_USAGE_ACCESS, MonitorPhase.WAITING_NOTIFICATIONS -> MonitorSnoozeOutcome.CANCELLED_PERMISSION
            MonitorPhase.WAITING_SELECTION -> MonitorSnoozeOutcome.CANCELLED_SCOPE
            MonitorPhase.OFF -> MonitorSnoozeOutcome.CANCELLED_STOPPED
            else -> MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
        }
        suspendMonitoring(phase, canceled, "prerequisite_missing", resetScope = service != null)
        return false
    }

    private fun waitingPhase(): MonitorPhase = when {
        !control().enabled -> MonitorPhase.OFF
        !source.hasAccess() -> MonitorPhase.WAITING_USAGE_ACCESS
        !notices.allowed() -> MonitorPhase.WAITING_NOTIFICATIONS
        apps.none { it.packageName in control().selected } -> MonitorPhase.WAITING_SELECTION
        else -> MonitorPhase.WAITING_SYSTEM
    }

    private fun backgroundRestricted(): Boolean = Build.VERSION.SDK_INT >= 28 &&
        application.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    private fun suspendMonitoring(phase: MonitorPhase, outcome: MonitorSnoozeOutcome, issue: String,
        newEnabled: Boolean = control().enabled, resetScope: Boolean) {
        val previous = control()
        val clock = MonitorClock.now()
        val pending = previous.pending
        endRuntime()
        notices.cancelReminder()
        store(previous.copy(enabled = newEnabled, pending = null, alertId = null,
            scopeStartWall = if (resetScope) nextScopeStart(clock, previous) else previous.scopeStartWall,
            snoozeOutcome = if (pending == null) previous.snoozeOutcome else outcome,
            snoozeDelay = if (pending == null) previous.snoozeDelay else null))
        if (pending != null) diagnostics.snooze(pending.id, outcome, clock, pending.dueElapsedMillis, null)
        clearUsage()
        transition(phase, issue)
    }

    private fun endRuntime() {
        worker.removeCallbacks(poll)
        promotionTicket.set(0)
        pendingStart = 0
        val previous = service
        service = null
        previous?.finish()
    }

    private fun schedule(delay: Long) {
        worker.removeCallbacks(poll)
        val clock = MonitorClock.now()
        expectedUptime = clock.uptime + delay
        expectedElapsed = clock.elapsed + delay
        worker.postAtTime(poll, expectedUptime)
    }

    private fun tick() {
        val active = service ?: return
        if (!active.foreground || activeGeneration != stopGeneration.get()) return
        val entered = MonitorClock.now()
        val power = source.power()
        diagnostics.tickEnter(entered, power, expectedUptime, expectedElapsed)
        var quality = MonitorDataQuality.UNAVAILABLE
        var issue: String? = null
        try {
            if (!checkPrerequisites()) {
                issue = "prerequisite_missing"
                return
            }
            val zone = ZoneId.systemDefault()
            val sample = MonitorClock.now()
            if (forcedClockCheck || lastClock?.let { !sample.wallConsistentWith(it) } == true) {
                markClockUncertain(sample, zone)
            }
            forcedClockCheck = false
            lastClock = sample
            val current = control()
            val uncertain = sample.wall < current.clockUncertainUntilWall
            val windowStart = (sample.wall - HISTORY_MILLIS).coerceAtLeast(0).let {
                if (uncertain) maxOf(it, current.scopeStartWall) else it
            }
            val pending = current.pending
            val dueWall = pending?.takeIf {
                sample.elapsed >= it.dueElapsedMillis && it.wallClockConsistent(sample.wall, sample.elapsed)
            }?.dueWallMillis
            diagnostics.queryStart(MonitorClock.now())
            val queryStarted = MonitorClock.now()
            val events = runCatching { source.read(windowStart, sample.wall + 1) }.getOrNull()
            val queried = MonitorClock.now()
            diagnostics.queryEnd(queried, queried.elapsed - queryStarted.elapsed)
            val settings = runCatching { readCommittedReminderSettings(application) }.getOrNull()
            val after = MonitorClock.now()
            val clockOkay = after.wallConsistentWith(sample)
            if (!clockOkay) {
                markClockUncertain(after, ZoneId.systemDefault())
                lastClock = after
            }
            val usableEvents = events?.takeIf { clockOkay && source.hasAccess() && zone == ZoneId.systemDefault() }
            val used = usableEvents?.let {
                UsageTimeline.fold(it, windowStart, sample.wall, zone, current.selected,
                    current.scopeStartWall, dueWall, power.allowsUse && source.power().allowsUse)
            }
            issue = when {
                !clockOkay || uncertain -> "clock_discontinuity"
                used == null -> "usage_query_unavailable"
                settings == null -> "reminder_settings_unavailable"
                else -> null
            }
            if (activeGeneration != stopGeneration.get() || !active.foreground) return
            if (!checkPrerequisites()) return
            decide(used, settings, usableEvents, windowStart, sample, zone, clockOkay && !uncertain)
            quality = when {
                used == null -> MonitorDataQuality.UNAVAILABLE
                uncertain || !used.todayComplete -> MonitorDataQuality.PARTIAL
                else -> MonitorDataQuality.COMPLETE
            }
            displayUsage(used, sample, quality)
            if (view.value.phase != MonitorPhase.RUNNING) transition(MonitorPhase.RUNNING, issue)
            else publish()
        } finally {
            diagnostics.tickEnd(MonitorClock.now(), quality, issue)
            if (service === active && active.foreground && activeGeneration == stopGeneration.get()) {
                schedule(if (source.power().interactive) INTERACTIVE_POLL_MILLIS else IDLE_POLL_MILLIS)
            }
        }
    }

    private fun markClockUncertain(clock: MonitorClock, zone: ZoneId) {
        val current = control()
        val midnight = Instant.ofEpochMilli(clock.wall).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        store(current.copy(clockUncertainUntilWall = midnight, scopeStartWall = clock.wall,
            pending = null, alertId = null,
            snoozeOutcome = if (current.pending == null) current.snoozeOutcome else MonitorSnoozeOutcome.CANCELLED_CLOCK,
            snoozeDelay = if (current.pending == null) current.snoozeDelay else null))
        notices.cancelReminder()
        current.pending?.let {
            diagnostics.snooze(it.id, MonitorSnoozeOutcome.CANCELLED_CLOCK, clock, it.dueElapsedMillis, null)
        }
    }

    private fun decide(used: UsageSnapshot?, settings: ReminderSettings?, events: List<UsageEvent>?,
        windowStart: Long, sample: MonitorClock, zone: ZoneId, clockOkay: Boolean) {
        val current = control()
        val pending = current.pending
        val decision = ReminderPolicy.evaluate(used, settings ?: ALL_REMINDERS_DISABLED, current.sentKeys,
            pending, current.scopeStartWall, sample.elapsed, current.bootCount, current.enabled,
            notices.allowed(), clockOkay)
        val dueNow = decision.snoozeStatus == SnoozeStatus.FIRED
        var outcome = decision.snoozeStatus.toOutcome()
        val beforeStore = MonitorClock.now()
        val ready = decision.reasons.isNotEmpty() && canSubmit(sample, zone, beforeStore)
        if (!ready && dueNow) outcome = MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
        val newAlert = if (ready && decision.newKeys.isNotEmpty() && current.bootCount >= 0) {
            UUID.randomUUID().toString()
        } else null
        val delay = if (pending != null && decision.snoozeDelayMillis != null) {
            (beforeStore.elapsed - pending.dueElapsedMillis).coerceAtLeast(0)
        } else null
        if (decision.pending !== pending || ready) {
            // Reserve before the Android side effect, so process death cannot repost the same trigger.
            store(current.copy(sentKeys = if (ready) current.sentKeys + decision.newKeys else current.sentKeys,
                pending = decision.pending,
                alertId = if (ready) newAlert else current.alertId,
                snoozeOutcome = outcome ?: current.snoozeOutcome,
                snoozeDelay = if (outcome == null) current.snoozeDelay else delay))
        }
        if (ready) {
            val snoozeRejected = if (dueNow && pending != null && events != null) {
                verifySnoozeUse(pending, events, windowStart, sample, zone, current)
            } else null
            if (snoozeRejected != null) outcome = snoozeRejected
            val reasons = decision.reasons.filter { snoozeRejected == null || it.type != ReminderPolicy.Type.SNOOZE }
            val notification = reasons.takeIf { it.isNotEmpty() }?.let {
                runCatching { notices.prepare(it, newAlert) }.getOrNull()
            }
            val attempted = notification != null && canSubmit(sample, zone, MonitorClock.now()) && notices.allowed() &&
                // This read commits submission relative to Stop, without holding the UI over a Binder call.
                stopGeneration.get() == activeGeneration && service?.foreground == true
            val submitted = if (attempted && notices.submit(checkNotNull(notification))) MonitorClock.now() else null
            val snoozeSubmitted = dueNow && snoozeRejected == null && submitted != null
            if (submitted != null) {
                diagnostics.notificationSubmitted(submitted, reasons.size, snoozeSubmitted)
                if (snoozeSubmitted && pending != null) {
                    val submissionDelay = (submitted.elapsed - pending.dueElapsedMillis).coerceAtLeast(0)
                    outcome = MonitorSnoozeOutcome.SUBMITTED
                    store(control().copy(snoozeOutcome = outcome, snoozeDelay = submissionDelay))
                    diagnostics.snooze(pending.id, outcome, submitted, pending.dueElapsedMillis, submissionDelay)
                    return
                }
            } else if (!attempted) {
                // A known absence of a system call does not spend any threshold's notification slot.
                store(control().copy(sentKeys = current.sentKeys, alertId = current.alertId))
                if (dueNow && snoozeRejected == null) outcome = MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
            } else {
                // Submission may have reached Android even if Binder reported failure.
                store(control().copy(alertId = null))
            }
        }
        if (pending != null && decision.pending == null && outcome != null) {
            store(control().copy(snoozeOutcome = outcome, snoozeDelay = delay))
            diagnostics.snooze(pending.id, outcome, beforeStore, pending.dueElapsedMillis, delay)
        }
    }

    private fun verifySnoozeUse(pending: ReminderPolicy.PendingSnooze, events: List<UsageEvent>,
        windowStart: Long, sample: MonitorClock, zone: ZoneId, current: MonitorControl): MonitorSnoozeOutcome? {
        val check = MonitorClock.now()
        if (!canSubmit(sample, zone, check)) return MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
        // Re-read the recent tail before dispatch to account for a selected app being left since the first query.
        val tailStart = maxOf(windowStart, sample.wall - MAX_EVIDENCE_AGE_MILLIS)
        val tail = runCatching { source.read(tailStart, check.wall + 1) }.getOrNull()
            ?: return MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
        val latest = UsageTimeline.fold(events.takeWhile { it.timeMillis < tailStart } + tail, windowStart,
            check.wall, zone, current.selected, current.scopeStartWall, pending.dueWallMillis,
            source.power().allowsUse)
        return when {
            latest.dueEvidence == UsageEvidence.INACTIVE || latest.currentEvidence == UsageEvidence.INACTIVE ->
                MonitorSnoozeOutcome.CANCELLED_NOT_USING
            latest.dueEvidence == UsageEvidence.ACTIVE && latest.currentEvidence == UsageEvidence.ACTIVE -> null
            else -> MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
        }
    }

    private fun canSubmit(sample: MonitorClock, zone: ZoneId, now: MonitorClock): Boolean =
        service?.foreground == true && stopGeneration.get() == activeGeneration && source.hasAccess() &&
            source.power().allowsUse && now.wallConsistentWith(sample) &&
            now.elapsed - sample.elapsed <= MAX_EVIDENCE_AGE_MILLIS && zone == ZoneId.systemDefault() &&
            Instant.ofEpochMilli(sample.wall).atZone(zone).toLocalDate() ==
                Instant.ofEpochMilli(now.wall).atZone(zone).toLocalDate()

    private fun SnoozeStatus.toOutcome(): MonitorSnoozeOutcome? = when (this) {
        SnoozeStatus.NONE, SnoozeStatus.PENDING -> null
        SnoozeStatus.FIRED -> MonitorSnoozeOutcome.SUBMISSION_FAILED
        SnoozeStatus.CANCELED_MONITORING -> MonitorSnoozeOutcome.CANCELLED_STOPPED
        SnoozeStatus.CANCELED_BOOT -> MonitorSnoozeOutcome.CANCELLED_REBOOT
        SnoozeStatus.CANCELED_SCOPE -> MonitorSnoozeOutcome.CANCELLED_SCOPE
        SnoozeStatus.CANCELED_CLOCK -> MonitorSnoozeOutcome.CANCELLED_CLOCK
        SnoozeStatus.DROPPED_INACTIVE -> MonitorSnoozeOutcome.CANCELLED_NOT_USING
        SnoozeStatus.DROPPED_UNKNOWN -> MonitorSnoozeOutcome.CANCELLED_UNCERTAIN
        SnoozeStatus.DROPPED_PERMISSION -> MonitorSnoozeOutcome.CANCELLED_PERMISSION
    }

    private fun displayUsage(used: UsageSnapshot?, sample: MonitorClock, quality: MonitorDataQuality) {
        val complete = quality == MonitorDataQuality.COMPLETE
        view.value = view.value.copy(
            todayByAppMillis = used?.todayByApp?.filterValues { complete || it > 0 }.orEmpty(),
            todayTotalMillis = used?.todayTotalMillis?.takeIf { complete || it > 0 },
            sessionMillis = used?.sessionUsedMillis?.takeIf { complete || it > 0 },
            dataQuality = quality, selectedToday = used?.today?.toString(),
            lastUpdateWallMillis = sample.wall, lastUpdateElapsedMillis = sample.elapsed
        )
    }

    private fun clearUsage() {
        view.value = view.value.copy(todayByAppMillis = emptyMap(), todayTotalMillis = null,
            sessionMillis = null, dataQuality = MonitorDataQuality.UNAVAILABLE, selectedToday = null)
    }

    private fun transition(phase: MonitorPhase, issue: String? = null) {
        publish(phase)
        diagnostics.phase(phase, control().enabled, view.value.usageAccess,
            view.value.notificationsAllowed, issue)
    }

    private fun publish(phase: MonitorPhase = view.value.phase) {
        val control = control()
        view.value = view.value.copy(initialized = true, enabledIntent = control.enabled, phase = phase,
            usageAccess = source.hasAccess(), notificationsAllowed = notices.allowed(),
            apps = apps, selectedPackages = control.selected, lastSnoozeOutcome = control.snoozeOutcome,
            lastSnoozeDelayMillis = control.snoozeDelay, snoozeDueElapsedMillis = control.pending?.dueElapsedMillis)
    }

    private fun nextScopeStart(clock: MonitorClock, previous: MonitorControl) =
        if (clock.wall == previous.scopeStartWall) clock.wall + 1 else clock.wall

    companion object {
        const val OPEN_SCREEN_EXTRA = "io.github.githuiy.starlive.OPEN_SCREEN_USE"
        const val STALE_AFTER_MILLIS = 135_000L
        private const val INTERACTIVE_POLL_MILLIS = 10_000L
        private const val IDLE_POLL_MILLIS = 60_000L
        private const val MAX_EVIDENCE_AGE_MILLIS = 2_000L
        private const val HISTORY_MILLIS = 48 * 60 * 60 * 1_000L
        private val ALL_REMINDERS_DISABLED = ReminderSettings(60, 60, 180, false, false, false)
        @Volatile private var instance: MonitorController? = null

        fun get(context: Context): MonitorController {
            val app = context.applicationContext
            return instance?.takeIf { it.application === app } ?: synchronized(this) {
                instance?.takeIf { it.application === app } ?: MonitorController(app).also { instance = it }
            }
        }
    }
}
