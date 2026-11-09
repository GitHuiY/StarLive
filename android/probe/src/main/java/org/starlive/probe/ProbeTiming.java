package org.starlive.probe;

/** Compares two Android monotonic clock readings without mixing their baselines. */
public final class ProbeTiming {
    private ProbeTiming() {
    }

    /**
     * All arguments are milliseconds from the corresponding monotonic clock.
     * Uptime excludes deep sleep; elapsed realtime includes it. The expected
     * pair must describe the same planned callback instant, and the actual pair
     * must describe the same observed callback instant.
     *
     * <p>Negative jitter is reported as zero. Extra sleep uses the original,
     * unclamped deltas, so a callback early in uptime but late in elapsed time
     * still exposes the full intervening sleep gap. An unrepresentable positive
     * result saturates at {@link Long#MAX_VALUE} instead of wrapping negative.
     *
     * @throws IllegalArgumentException if a clock reading is negative
     */
    public static Timing compare(long expectedUptime, long expectedElapsed,
            long actualUptime, long actualElapsed) {
        if (expectedUptime < 0L || expectedElapsed < 0L
                || actualUptime < 0L || actualElapsed < 0L) {
            throw new IllegalArgumentException("Monotonic clock readings must be nonnegative");
        }
        // Subtracting two nonnegative longs is representable as a signed long.
        long uptimeDelta = actualUptime - expectedUptime;
        long elapsedDelta = actualElapsed - expectedElapsed;
        long extraSleepGap = 0L;
        if (elapsedDelta > uptimeDelta) {
            long difference = elapsedDelta - uptimeDelta;
            extraSleepGap = difference < 0L ? Long.MAX_VALUE : difference;
        }
        return new Timing(Math.max(0L, uptimeDelta), Math.max(0L, elapsedDelta),
                extraSleepGap);
    }

    public static final class Timing {
        public final long uptimeJitter;
        public final long elapsedJitter;
        public final long extraSleepGap;

        private Timing(long uptimeJitter, long elapsedJitter, long extraSleepGap) {
            this.uptimeJitter = uptimeJitter;
            this.elapsedJitter = elapsedJitter;
            this.extraSleepGap = extraSleepGap;
        }
    }
}
