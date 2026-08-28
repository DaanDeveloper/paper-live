package io.papermc.paper.plugin.debug;

import org.jspecify.annotations.NullMarked;

/** A time-ordered diagnostic displayed by the integrated PaperLive debugger. */
@NullMarked
public sealed interface PaperLiveDebugRecord permits PaperLiveDebugException, PaperLiveBuildFailure, PaperLiveCommandTrace, PaperLiveEventTrace {

    long sequence();

    long occurredAtEpochMillis();
}
