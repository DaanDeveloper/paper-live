package io.papermc.paper.plugin.debug;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Immutable compiler/build failure captured from a PaperLive source project. */
@NullMarked
public record PaperLiveBuildFailure(
    long sequence,
    long occurredAtEpochMillis,
    String projectName,
    String buildSystem,
    String command,
    @Nullable Integer exitCode,
    String reason,
    String logFile,
    String output
) implements PaperLiveDebugRecord {
}
