package io.github.githuiy.starlive.core.monitor;

import java.util.Objects;

/** A normalized Android usage event, retained only for the current query. */
public final class UsageEvent {
    public enum Type {
        RESUME, PAUSE, STOP, SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE,
        KEYGUARD_SHOWN, KEYGUARD_HIDDEN, DEVICE_SHUTDOWN, DEVICE_STARTUP
    }

    public final Type type;
    public final long timeMillis;
    public final String packageName;
    public final String className;

    public UsageEvent(Type type, long timeMillis, String packageName, String className) {
        this.type = Objects.requireNonNull(type);
        this.timeMillis = timeMillis;
        this.packageName = packageName;
        this.className = className;
    }
}
