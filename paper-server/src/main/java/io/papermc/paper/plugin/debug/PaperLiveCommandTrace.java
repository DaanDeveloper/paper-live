package io.papermc.paper.plugin.debug;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Immutable trace of one Bukkit command execution. */
@NullMarked
public record PaperLiveCommandTrace(
    long sequence,
    long occurredAtEpochMillis,
    long traceId,
    long rootSpanId,
    String commandLine,
    String commandLabel,
    String senderName,
    String senderType,
    @Nullable String playerUuid,
    @Nullable String world,
    String ownerName,
    String ownerVersion,
    String commandClass,
    String threadName,
    long durationNanos,
    boolean executorResult,
    PaperLiveDebugException.@Nullable ThrowableSnapshot throwable
) implements PaperLiveDebugRecord {

    public boolean completedNormally() {
        return this.throwable == null;
    }
}
