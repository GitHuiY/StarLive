package org.starlive.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free checks for monotonic diagnostics and cancellation ordering. */
public final class ProbeRuntimeChecks {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        timingExamples();
        timingBoundaries();
        gateRunLifecycle();
        queuedCallbackCannotEnterAfterCancel();
        cancellationWaitsForEnteredDispatch();
        actionFailureReleasesTheGate();
        System.out.println("ProbeRuntime checks passed: " + assertions);
    }

    private static void timingExamples() {
        assertTiming(1000, 7000, 1000, 7000, 0, 0, 0,
                "Exactly on schedule");
        assertTiming(1000, 7000, 1250, 7250, 250, 250, 0,
                "A busy but awake worker is equally late on both clocks");
        assertTiming(1000, 7000, 1015, 14015, 15, 7015, 7000,
                "Seven seconds of sleep with fifteen milliseconds of awake delay");
        assertTiming(1000, 7000, 1350, 14350, 350, 7350, 7000,
                "Sleep and awake scheduling delay are kept distinct");
        assertTiming(1000, 7000, 990, 7010, 0, 10, 20,
                "An early uptime callback still reveals the full sleep difference");
        assertTiming(1000, 7000, 990, 6990, 0, 0, 0,
                "An equally early callback is not jitter or sleep");
        assertTiming(1000, 7000, 1100, 6990, 100, 0, 0,
                "An inconsistent clock pair cannot create negative diagnostics");
        assertTiming(100, 100000, 600, 101000, 500, 1000, 500,
                "Preexisting elapsed-versus-uptime offset is not counted as new sleep");
        assertTiming(10, 20, 11, 22, 1, 2, 1,
                "One millisecond of extra sleep remains visible");
    }

    private static void timingBoundaries() {
        assertTiming(0, 0, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, 0,
                "Large equal clock advances do not overflow");
        assertTiming(Long.MAX_VALUE, 0, 0, Long.MAX_VALUE,
                0, Long.MAX_VALUE, Long.MAX_VALUE,
                "An unrepresentable sleep difference saturates");
        assertTiming(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0, 0,
                "Large equal backwards readings produce no negative fields");
        expectNegativeClockRejected(-1, 0, 0, 0);
        expectNegativeClockRejected(0, -1, 0, 0);
        expectNegativeClockRejected(0, 0, -1, 0);
        expectNegativeClockRejected(0, 0, 0, -1);
    }

    private static void gateRunLifecycle() {
        RunGate gate = new RunGate();
        AtomicInteger entered = new AtomicInteger();
        check(!gate.isCurrent(0L), "An unstarted gate has no current token");
        check(!gate.runIfCurrent(0L, entered::incrementAndGet),
                "An unstarted gate must not dispatch");
        long first = gate.start();
        check(gate.isCurrent(first), "Starting activates the returned token");
        check(gate.runIfCurrent(first, entered::incrementAndGet),
                "The current token may dispatch");
        long second = gate.start();
        check(second != first && !gate.isCurrent(first) && gate.isCurrent(second),
                "Starting a new run invalidates the old token");
        check(!gate.runIfCurrent(first, entered::incrementAndGet),
                "A superseded run must not dispatch");
        check(gate.runIfCurrent(second, entered::incrementAndGet),
                "A new current token may dispatch");
        gate.cancel();
        gate.cancel();
        check(!gate.isCurrent(second), "Cancellation is idempotent");
        check(!gate.runIfCurrent(second, entered::incrementAndGet),
                "A cancelled run must not dispatch");
        long third = gate.start();
        check(third != first && third != second && gate.isCurrent(third),
                "A restart creates a distinct token");
        check(entered.get() == 2, "Rejected stale runs never entered their actions");
        boolean nullRejected = false;
        try {
            gate.runIfCurrent(third, null);
        } catch (NullPointerException expected) {
            nullRejected = true;
        }
        check(nullRejected, "Null dispatch actions fail explicitly");
    }

    private static void queuedCallbackCannotEnterAfterCancel() throws Exception {
        RunGate gate = new RunGate();
        long token = gate.start();
        AtomicInteger entered = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch workerHeld = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            Future<?> hold = worker.submit(() -> {
                workerHeld.countDown();
                await(releaseWorker);
            });
            check(workerHeld.await(2, TimeUnit.SECONDS), "Test worker entered its prior task");
            Future<Boolean> staleCallback = worker.submit(
                    () -> gate.runIfCurrent(token, entered::incrementAndGet));
            gate.cancel();
            releaseWorker.countDown();
            hold.get(2, TimeUnit.SECONDS);
            check(!staleCallback.get(2, TimeUnit.SECONDS),
                    "A callback queued before stop cannot enter after cancel returned");
            check(entered.get() == 0, "No old notification action began after stop");
        } finally {
            releaseWorker.countDown();
            worker.shutdownNow();
            worker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void cancellationWaitsForEnteredDispatch() throws Exception {
        RunGate gate = new RunGate();
        long token = gate.start();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch allowActionToFinish = new CountDownLatch(1);
        CountDownLatch cancelAboutToRun = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            Future<Boolean> inFlight = threads.submit(() -> gate.runIfCurrent(token, () -> {
                actionEntered.countDown();
                await(allowActionToFinish);
                order.add("action-finished");
            }));
            check(actionEntered.await(2, TimeUnit.SECONDS),
                    "The in-flight dispatch reached its cancellation boundary");
            Future<?> cancel = threads.submit(() -> {
                cancelAboutToRun.countDown();
                gate.cancel();
                order.add("cancel-returned");
            });
            check(cancelAboutToRun.await(2, TimeUnit.SECONDS), "Cancellation thread was scheduled");
            boolean waited = false;
            try {
                cancel.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                waited = true;
            }
            check(waited, "Cancellation waits for an already-entered dispatch");
            allowActionToFinish.countDown();
            check(inFlight.get(2, TimeUnit.SECONDS), "Already-entered dispatch may finish");
            cancel.get(2, TimeUnit.SECONDS);
            check(order.size() == 2 && "action-finished".equals(order.get(0))
                    && "cancel-returned".equals(order.get(1)),
                    "The dispatch completed before cancellation returned");
            check(!gate.runIfCurrent(token, () -> {
                throw new AssertionError("A stale action entered after cancellation");
            }), "No old action may enter after cancellation completes");
        } finally {
            allowActionToFinish.countDown();
            threads.shutdownNow();
            threads.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void actionFailureReleasesTheGate() throws Exception {
        RunGate gate = new RunGate();
        long token = gate.start();
        boolean propagated = false;
        try {
            gate.runIfCurrent(token, () -> {
                throw new IllegalStateException("simulated notification failure");
            });
        } catch (IllegalStateException expected) {
            propagated = true;
        }
        check(propagated, "Dispatch exceptions are not reported as successful submissions");
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            otherThread.submit(gate::cancel).get(2, TimeUnit.SECONDS);
            check(!gate.isCurrent(token), "A failed action releases the lock for cancellation");
        } finally {
            otherThread.shutdownNow();
            otherThread.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void assertTiming(long expectedUptime, long expectedElapsed,
            long actualUptime, long actualElapsed, long wantedUptime,
            long wantedElapsed, long wantedSleep, String scenario) {
        ProbeTiming.Timing timing = ProbeTiming.compare(expectedUptime, expectedElapsed,
                actualUptime, actualElapsed);
        check(timing.uptimeJitter == wantedUptime && timing.elapsedJitter == wantedElapsed
                && timing.extraSleepGap == wantedSleep, scenario);
    }

    private static void expectNegativeClockRejected(long expectedUptime,
            long expectedElapsed, long actualUptime, long actualElapsed) {
        boolean rejected = false;
        try {
            ProbeTiming.compare(expectedUptime, expectedElapsed, actualUptime, actualElapsed);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "Negative monotonic clock readings are rejected");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Test synchronization timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test synchronization interrupted", failure);
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
