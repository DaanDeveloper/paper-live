package io.papermc.paper.plugin.debug;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** One completed execution of a plugin-owned scheduled task. */
@NullMarked
public record PaperLiveTaskTrace(
    long sequence,
    long occurredAtEpochMillis,
    String pluginName,
    String pluginVersion,
    String taskId,
    String schedulerType,
    String taskClass,
    String registrationSite,
    String threadName,
    long durationNanos,
    boolean asynchronous,
    boolean repeating,
    @Nullable String failure
) {

    public boolean successful() {
        return this.failure == null;
    }
}
