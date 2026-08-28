package io.papermc.paper.plugin.debug;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Completed trace of one Bukkit event and every registered handler in dispatch order. */
@NullMarked
public record PaperLiveEventTrace(
    long sequence,
    long occurredAtEpochMillis,
    long traceId,
    PaperLiveDebugException.EventSnapshot eventBefore,
    PaperLiveDebugException.EventSnapshot eventAfter,
    long durationNanos,
    List<HandlerExecution> handlers
) implements PaperLiveDebugRecord {

    public PaperLiveEventTrace {
        handlers = List.copyOf(handlers);
    }

    public record HandlerExecution(
        int index,
        PaperLiveDebugException.HandlerSnapshot handler,
        String status,
        long durationNanos,
        @Nullable Boolean cancelledBefore,
        @Nullable Boolean cancelledAfter
    ) {
    }
}
