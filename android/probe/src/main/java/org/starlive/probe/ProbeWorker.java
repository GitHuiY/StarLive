package org.starlive.probe;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/** One serial queue owns usage queries, reminder state and diagnostic I/O. */
final class ProbeWorker {
    private static final HandlerThread THREAD;
    private static final Handler HANDLER;
    static volatile String lastUnhandledError = "";
    static volatile long lastUnhandledErrorElapsed;
    static {
        THREAD = new HandlerThread("starlive-probe-worker");
        THREAD.start();
        HANDLER = new Handler(THREAD.getLooper());
    }

    static Handler handler() { return HANDLER; }
    static void post(Runnable task) {
        HANDLER.post(() -> {
            try { task.run(); }
            catch (RuntimeException failure) {
                // Keep the serial queue alive; a subsequent snapshot exposes the failure.
                lastUnhandledError = failure.getClass().getSimpleName();
                lastUnhandledErrorElapsed = android.os.SystemClock.elapsedRealtime();
            }
        });
    }
    static boolean isWorkerThread() { return Looper.myLooper() == THREAD.getLooper(); }
    static void requireWorker() {
        if (!isWorkerThread()) throw new IllegalStateException("Probe state requires its serial worker");
    }
}
