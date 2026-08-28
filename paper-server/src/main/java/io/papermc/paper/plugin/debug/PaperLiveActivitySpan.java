package io.papermc.paper.plugin.debug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** One completed unit of work within a command-correlated PaperLive activity. */
@NullMarked
public record PaperLiveActivitySpan(
    long traceId,
    long spanId,
    long parentSpanId,
    long startedAtEpochMillis,
    long durationNanos,
    String name,
    String category,
    String threadName,
    boolean successful,
    @Nullable String failureReason,
    Map<String, String> details
) {

    public PaperLiveActivitySpan {
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
