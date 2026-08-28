package io.papermc.paper.plugin.debug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Immutable description of an exception thrown by a plugin event handler.
 *
 * <p>The snapshot deliberately contains only values that can be serialized. It never retains
 * plugin, event, entity, or world instances, allowing old plugin class loaders to be collected
 * after a PaperLive refresh.</p>
 */
@NullMarked
public record PaperLiveDebugException(
    long sequence,
    long occurredAtEpochMillis,
    EventSnapshot event,
    PluginSnapshot failingPlugin,
    String listenerClass,
    int failingHandlerIndex,
    List<HandlerSnapshot> handlers,
    ThrowableSnapshot throwable
) implements PaperLiveDebugRecord {

    public PaperLiveDebugException {
        handlers = List.copyOf(handlers);
    }

    public int previousHandlerCount() {
        return this.failingHandlerIndex;
    }

    public record EventSnapshot(
        String name,
        String className,
        boolean asynchronous,
        String threadName,
        @Nullable Boolean cancelled,
        Map<String, String> context
    ) {

        public EventSnapshot {
            context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
        }
    }

    public record PluginSnapshot(String name, String version, boolean enabled) {
    }

    public record HandlerSnapshot(
        int index,
        PluginSnapshot plugin,
        String listenerClass,
        String priority,
        boolean ignoreCancelled
    ) {
    }

    public record ThrowableSnapshot(
        String type,
        @Nullable String message,
        List<StackFrameSnapshot> stackTrace,
        @Nullable ThrowableSnapshot cause
    ) {

        public ThrowableSnapshot {
            stackTrace = List.copyOf(stackTrace);
        }
    }

    public record StackFrameSnapshot(
        String className,
        String methodName,
        @Nullable String fileName,
        int lineNumber
    ) {
    }
}
