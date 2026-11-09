package io.github.githuiy.starlive.monitor

data class MonitorApp(val packageName: String, val label: String)

enum class MonitorPhase {
    OFF, STARTING, RUNNING, WAITING_USAGE_ACCESS, WAITING_NOTIFICATIONS, WAITING_SELECTION,
    WAITING_SYSTEM, STALE, STORAGE_ERROR
}

enum class MonitorDataQuality { UNAVAILABLE, PARTIAL, COMPLETE }

enum class MonitorSnoozeOutcome {
    NONE, PENDING, SUBMITTED, CANCELLED_NOT_USING, CANCELLED_UNCERTAIN, CANCELLED_SCOPE,
    CANCELLED_STOPPED, CANCELLED_REBOOT, CANCELLED_CLOCK, CANCELLED_PERMISSION, SUBMISSION_FAILED
}

data class MonitorSnapshot(
    val enabledIntent: Boolean = false,
    val phase: MonitorPhase = MonitorPhase.OFF,
    val usageAccess: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    val apps: List<MonitorApp> = emptyList(),
    val todayByAppMillis: Map<String, Long> = emptyMap(),
    val todayTotalMillis: Long? = null,
    val sessionMillis: Long? = null,
    val dataQuality: MonitorDataQuality = MonitorDataQuality.UNAVAILABLE,
    val lastUpdateWallMillis: Long? = null,
    val lastUpdateElapsedMillis: Long? = null,
    val lastSnoozeOutcome: MonitorSnoozeOutcome = MonitorSnoozeOutcome.NONE,
    val lastSnoozeDelayMillis: Long? = null,
    val snoozeDueElapsedMillis: Long? = null,
    val initialized: Boolean = false,
    val selectedToday: String? = null
)
