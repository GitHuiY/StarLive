package org.starlive.probe;

/**
 * Serializes cancellation with the final dispatch of one monitoring run.
 *
 * <p>Use the token to reject stale queued callbacks. Keep usage queries, policy
 * evaluation and file writes outside this gate; put only the final notification
 * submission inside {@link #runIfCurrent(long, Runnable)}.
 *
 * <p>The action executes while this gate's monitor is held. Therefore cancellation
 * waits for an action that already entered, including a synchronous notification
 * Binder submission. There is no time bound on that wait. This is the strong
 * ordering tradeoff: after {@code cancel()} returns, an old action cannot begin.
 * A lightweight "begin dispatch" check followed by an unlocked action would not
 * provide that guarantee. The gate cannot retract a notification the system has
 * already accepted, nor undo other effects of an action that already entered.
 */
public final class RunGate {
    private long generation;
    private boolean active;

    /** Starts a new run and invalidates any previous run token. */
    public synchronized long start() {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("Run token space exhausted");
        }
        generation++;
        active = true;
        return generation;
    }

    /**
     * Invalidates this run before returning. May wait for an already-entered
     * dispatch action to finish; queued actions do not get to enter afterward.
     */
    public synchronized void cancel() {
        active = false;
    }

    public synchronized boolean isCurrent(long token) {
        return active && token == generation;
    }

    /**
     * Runs one final dispatch under the cancellation lock if its token is current.
     * Returns false without calling the action for a cancelled or superseded run.
     * Exceptions from the action propagate to the caller and release the lock.
     */
    public synchronized boolean runIfCurrent(long token, Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        if (!active || token != generation) {
            return false;
        }
        action.run();
        return true;
    }
}
