package io.papermc.paper.plugin.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.event.Event;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Captures a bounded history of plugin event exceptions for the live debugger transport. */
@NullMarked
public final class PaperLiveDebugger {

    private static final PaperLiveDebugger INSTANCE = new PaperLiveDebugger();
    private static final int DEFAULT_HISTORY_LIMIT = 250;
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int MAX_FRAMES_PER_THROWABLE = 256;
    private static final int MAX_ACTIVITY_SPANS = 1_000;
    private static final int MAX_EVENT_TRACES_PER_SECOND = 200;
    private static final int MAX_TASK_TRACES = 500;

    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong nextHandlerRegistrationSequence = new AtomicLong();
    private final AtomicLong nextTraceId = new AtomicLong();
    private final AtomicLong nextSpanId = new AtomicLong();
    private final AtomicLong eventTraceSecond = new AtomicLong();
    private final AtomicInteger eventTracesThisSecond = new AtomicInteger();
    private final AtomicLong droppedEventTraces = new AtomicLong();
    private final Deque<PaperLiveDebugRecord> debugHistory = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Consumer<PaperLiveDebugRecord>> recordListeners = new CopyOnWriteArrayList<>();
    private final List<PaperLiveHandlerRegistration> handlerRegistrations = new ArrayList<>();
    private final Set<String> knownPlayerNames = new LinkedHashSet<>();
    private final Deque<PaperLiveActivitySpan> activitySpans = new ArrayDeque<>();
    private final Deque<PaperLiveTaskTrace> taskTraces = new ArrayDeque<>();
    private final ThreadLocal<TraceContext> currentTrace = new ThreadLocal<>();
    private volatile EventTraceFilter eventTraceFilter = EventTraceFilter.create(true, "PlayerInteractEvent", "", "");
    private final int historyLimit;

    public PaperLiveDebugger() {
        this(DEFAULT_HISTORY_LIMIT);
    }

    public static PaperLiveDebugger instance() {
        return INSTANCE;
    }

    PaperLiveDebugger(int historyLimit) {
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        this.historyLimit = historyLimit;
    }

    /**
     * Captures an event failure without retaining references to runtime server or plugin objects.
     * This method must never interfere with the server's normal exception reporting path.
     */
    public void captureEventException(Event event, RegisteredListener[] listeners, int failingHandlerIndex, Throwable throwable) {
        try {
            PaperLiveDebugException snapshot = new PaperLiveDebugException(
                this.nextSequence.incrementAndGet(),
                System.currentTimeMillis(),
                PaperLiveEventContext.snapshot(event),
                pluginSnapshot(listeners[failingHandlerIndex].getPlugin()),
                listeners[failingHandlerIndex].getListener().getClass().getName(),
                failingHandlerIndex,
                handlerSnapshots(listeners),
                throwableSnapshot(throwable, 0)
            );
            this.add(snapshot);
            this.publish(snapshot);
        } catch (Throwable ignored) {
            // A diagnostic feature must never hide or replace the plugin's original exception.
        }
    }

    public void captureBuildFailure(
        String projectName,
        String buildSystem,
        String command,
        @Nullable Integer exitCode,
        String reason,
        String logFile,
        String output
    ) {
        PaperLiveBuildFailure failure = new PaperLiveBuildFailure(
            this.nextSequence.incrementAndGet(),
            System.currentTimeMillis(),
            projectName,
            buildSystem,
            command,
            exitCode,
            reason,
            logFile,
            output
        );
        this.add(failure);
        this.publish(failure);
    }

    public void captureCommand(
        CommandSender sender,
        Command command,
        String commandLine,
        String commandLabel,
        long durationNanos,
        boolean executorResult,
        @Nullable Throwable throwable
    ) {
        long traceId = this.nextTraceId.incrementAndGet();
        long rootSpanId = this.nextSpanId.incrementAndGet();
        this.captureCommand(sender, command, commandLine, commandLabel, durationNanos, executorResult, throwable, traceId, rootSpanId);
    }

    private void captureCommand(
        CommandSender sender,
        Command command,
        String commandLine,
        String commandLabel,
        long durationNanos,
        boolean executorResult,
        @Nullable Throwable throwable,
        long traceId,
        long rootSpanId
    ) {
        try {
            Plugin owner = command instanceof PluginIdentifiableCommand pluginCommand ? pluginCommand.getPlugin() : null;
            Player player = sender instanceof Player playerSender ? playerSender : null;
            PaperLiveCommandTrace trace = new PaperLiveCommandTrace(
                this.nextSequence.incrementAndGet(),
                System.currentTimeMillis(),
                traceId,
                rootSpanId,
                sanitizeCommandLine(commandLine, commandLabel),
                commandLabel,
                sender.getName(),
                sender.getClass().getName(),
                player == null ? null : player.getUniqueId().toString(),
                player == null ? null : player.getWorld().getName(),
                owner == null ? "Server" : owner.getPluginMeta().getName(),
                owner == null ? "" : owner.getPluginMeta().getVersion(),
                command.getClass().getName(),
                Thread.currentThread().getName(),
                durationNanos,
                executorResult,
                throwable == null ? null : throwableSnapshot(throwable, 0)
            );
            this.add(trace);
            this.publish(trace);
            PaperLiveScenarioRecorder.instance().recordCommand(trace);
        } catch (Throwable ignored) {
            // Command tracing must never change command execution or error propagation.
        }
    }

    public CommandTraceScope beginCommand(CommandSender sender, Command command, String commandLine, String commandLabel) {
        long traceId = this.nextTraceId.incrementAndGet();
        long rootSpanId = this.nextSpanId.incrementAndGet();
        TraceContext previous = this.currentTrace.get();
        this.currentTrace.set(new TraceContext(traceId, rootSpanId));
        return new CommandTraceScope(this, sender, command, commandLine, commandLabel, traceId, rootSpanId, previous);
    }

    public @Nullable TraceContext captureTraceContext() {
        return this.currentTrace.get();
    }

    public Runnable wrapAsync(
        @Nullable TraceContext parent,
        String name,
        String category,
        Map<String, String> details,
        Runnable action
    ) {
        if (parent == null) {
            return action;
        }
        return () -> {
            TraceContext previous = this.currentTrace.get();
            this.currentTrace.set(parent);
            try (ActivitySpanScope span = this.beginSpan(name, category, details)) {
                try {
                    action.run();
                } catch (RuntimeException | Error throwable) {
                    span.fail(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    throw throwable;
                }
            } finally {
                this.restoreTrace(previous);
            }
        };
    }

    public ActivitySpanScope beginSpan(String name, String category, Map<String, String> details) {
        TraceContext parent = this.currentTrace.get();
        if (parent == null) {
            return ActivitySpanScope.noop();
        }
        long spanId = this.nextSpanId.incrementAndGet();
        TraceContext previous = parent;
        this.currentTrace.set(new TraceContext(parent.traceId(), spanId));
        return new ActivitySpanScope(this, parent.traceId(), spanId, parent.spanId(), name, category, details, previous);
    }

    public synchronized List<PaperLiveActivitySpan> activitySpans(long traceId) {
        return this.activitySpans.stream().filter(span -> span.traceId() == traceId).toList();
    }

    public void updateEventTraceFilter(boolean enabled, String events, String plugins, String players) {
        this.eventTraceFilter = EventTraceFilter.create(enabled, events, plugins, players);
    }

    public EventTraceFilter eventTraceFilter() {
        return this.eventTraceFilter;
    }

    public long droppedEventTraceCount() {
        return this.droppedEventTraces.get();
    }

    public @Nullable EventTraceSession beginEventTrace(Event event, RegisteredListener[] listeners) {
        try {
            EventTraceFilter filter = this.eventTraceFilter;
            if (!filter.enabled() || !matches(filter.events(), event.getEventName())) {
                return null;
            }
            if (!filter.plugins().isEmpty() && Arrays.stream(listeners).noneMatch(listener -> matches(filter.plugins(), listener.getPlugin().getPluginMeta().getName()))) {
                return null;
            }
            if (!filter.players().isEmpty()) {
                if (!(event instanceof PlayerEvent playerEvent) || !matches(filter.players(), playerEvent.getPlayer().getName())) {
                    return null;
                }
            }
            if (!this.acquireEventTracePermit()) {
                this.droppedEventTraces.incrementAndGet();
                return null;
            }
            TraceContext context = this.currentTrace.get();
            PaperLiveDebugException.EventSnapshot eventBefore = PaperLiveEventContext.snapshot(event);
            ActivitySpanScope activity = this.beginSpan("Event " + event.getEventName(), "EVENT", Map.of("event", event.getEventName()));
            return new EventTraceSession(this, event, context == null ? 0 : context.traceId(), activity, eventBefore);
        } catch (Throwable ignored) {
            // Tracing must never prevent an event from being dispatched.
            return null;
        }
    }

    private boolean acquireEventTracePermit() {
        long second = System.currentTimeMillis() / 1_000L;
        long observed = this.eventTraceSecond.get();
        if (observed != second && this.eventTraceSecond.compareAndSet(observed, second)) {
            this.eventTracesThisSecond.set(0);
        }
        return this.eventTracesThisSecond.incrementAndGet() <= MAX_EVENT_TRACES_PER_SECOND;
    }

    private static boolean matches(List<String> filters, String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return filters.stream().anyMatch(filter -> filter.equals("*") || normalized.contains(filter));
    }

    private synchronized void addActivitySpan(PaperLiveActivitySpan span) {
        while (this.activitySpans.size() >= MAX_ACTIVITY_SPANS) {
            this.activitySpans.removeFirst();
        }
        this.activitySpans.addLast(span);
    }

    private void restoreTrace(@Nullable TraceContext context) {
        if (context == null) {
            this.currentTrace.remove();
        } else {
            this.currentTrace.set(context);
        }
    }

    public synchronized List<PaperLiveDebugRecord> recentRecords() {
        return List.copyOf(this.debugHistory);
    }

    public synchronized List<PaperLiveDebugException> recentExceptions() {
        return this.debugHistory.stream()
            .filter(PaperLiveDebugException.class::isInstance)
            .map(PaperLiveDebugException.class::cast)
            .toList();
    }

    public void captureTaskExecution(
        Plugin plugin,
        String taskId,
        String schedulerType,
        Class<?> taskClass,
        String registrationSite,
        boolean asynchronous,
        boolean repeating,
        long durationNanos,
        @Nullable Throwable failure
    ) {
        try {
            String failureMessage = failure == null ? null : failure.getClass().getName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            PaperLiveTaskTrace trace = new PaperLiveTaskTrace(
                this.nextSequence.incrementAndGet(),
                System.currentTimeMillis(),
                plugin.getPluginMeta().getName(),
                plugin.getPluginMeta().getVersion(),
                taskId,
                schedulerType,
                taskClass.getName(),
                registrationSite,
                Thread.currentThread().getName(),
                durationNanos,
                asynchronous,
                repeating,
                failureMessage
            );
            synchronized (this) {
                while (this.taskTraces.size() >= MAX_TASK_TRACES) {
                    this.taskTraces.removeFirst();
                }
                this.taskTraces.addLast(trace);
            }
        } catch (Throwable ignored) {
            // Task tracing must never alter scheduler behavior or exception propagation.
        }
    }

    public synchronized List<PaperLiveTaskTrace> recentTaskTraces() {
        return List.copyOf(this.taskTraces);
    }

    public void captureAsyncViolation(String reason, Throwable stack) {
        try {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            Plugin owner = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(plugin -> plugin.getClass().getClassLoader() == contextClassLoader)
                .findFirst()
                .orElse(null);
            StackTraceElement source = Arrays.stream(stack.getStackTrace())
                .filter(frame -> !frame.getClassName().equals("org.spigotmc.AsyncCatcher"))
                .findFirst()
                .orElse(null);
            String sourceText = source == null ? "unknown" : source.toString();
            PaperLiveTaskTrace trace = new PaperLiveTaskTrace(
                this.nextSequence.incrementAndGet(),
                System.currentTimeMillis(),
                owner == null ? "Unattributed" : owner.getPluginMeta().getName(),
                owner == null ? "" : owner.getPluginMeta().getVersion(),
                "—",
                "ASYNC_VIOLATION",
                source == null ? "unknown" : source.getClassName(),
                sourceText,
                Thread.currentThread().getName(),
                0L,
                true,
                false,
                "Unsafe asynchronous API access: " + reason
            );
            synchronized (this) {
                while (this.taskTraces.size() >= MAX_TASK_TRACES) {
                    this.taskTraces.removeFirst();
                }
                this.taskTraces.addLast(trace);
            }
        } catch (Throwable ignored) {
            // The normal AsyncCatcher exception remains authoritative if attribution is unavailable.
        }
    }

    public static String captureTaskRegistrationSite() {
        try {
            return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("org.bukkit.craftbukkit.scheduler."))
                .filter(frame -> !frame.getClassName().startsWith("io.papermc.paper.threadedregions.scheduler."))
                .filter(frame -> !frame.getClassName().equals(PaperLiveDebugger.class.getName()))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                .orElse("unknown"));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    public Subscription subscribe(Consumer<PaperLiveDebugRecord> listener) {
        this.recordListeners.add(listener);
        return () -> this.recordListeners.remove(listener);
    }

    public synchronized void clearRecords() {
        this.debugHistory.clear();
        this.taskTraces.clear();
    }

    public synchronized void recordEventRegistrations(Class<? extends Event> eventType, Iterable<RegisteredListener> listeners) {
        for (RegisteredListener listener : listeners) {
            EventPriority priority = listener.getPriority();
            this.handlerRegistrations.add(new PaperLiveHandlerRegistration(
                this.nextHandlerRegistrationSequence.incrementAndGet(),
                eventType.getSimpleName(),
                eventType.getName(),
                listener.getPlugin().getPluginMeta().getName(),
                listener.getPlugin().getPluginMeta().getVersion(),
                listener.getListener().getClass().getName(),
                priority.name(),
                priority.getSlot(),
                listener.getPlugin().isEnabled(),
                listener.isIgnoringCancelled()
            ));
        }
    }

    public synchronized void removeEventRegistrations(Plugin plugin) {
        String pluginName = plugin.getPluginMeta().getName();
        this.handlerRegistrations.removeIf(registration -> registration.pluginName().equalsIgnoreCase(pluginName));
    }

    public synchronized void clearEventRegistrations() {
        this.handlerRegistrations.clear();
    }

    public synchronized List<PaperLiveHandlerRegistration> registeredHandlers() {
        return List.copyOf(this.handlerRegistrations);
    }

    public void observeEvent(Event event) {
        try {
            PaperLiveScenarioRecorder.instance().observe(event);
            if (event instanceof PlayerEvent playerEvent) {
                String playerName = playerEvent.getPlayer().getName();
                synchronized (this) {
                    this.knownPlayerNames.removeIf(name -> name.equalsIgnoreCase(playerName));
                    if (!(event instanceof PlayerQuitEvent)) {
                        this.knownPlayerNames.add(playerName);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Selector bookkeeping must never affect event dispatch.
        }
    }

    public synchronized List<String> knownPlayerNames() {
        return List.copyOf(this.knownPlayerNames);
    }

    private synchronized void add(PaperLiveDebugRecord snapshot) {
        while (this.debugHistory.size() >= this.historyLimit) {
            this.debugHistory.removeFirst();
        }
        this.debugHistory.addLast(snapshot);
    }

    private void publish(PaperLiveDebugRecord snapshot) {
        for (Consumer<PaperLiveDebugRecord> listener : this.recordListeners) {
            try {
                listener.accept(snapshot);
            } catch (Throwable ignored) {
                // Debug consumers cannot interfere with event dispatch or other consumers.
            }
        }
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {

        @Override
        void close();
    }

    public record TraceContext(long traceId, long spanId) {
    }

    public record EventTraceFilter(boolean enabled, String eventInput, String pluginInput, String playerInput, List<String> events, List<String> plugins, List<String> players) {

        private static EventTraceFilter create(boolean enabled, String events, String plugins, String players) {
            return new EventTraceFilter(enabled, events, plugins, players, tokens(events), tokens(plugins), tokens(players));
        }

        private static List<String> tokens(String value) {
            return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(token -> !token.isEmpty())
                .map(token -> token.toLowerCase(java.util.Locale.ROOT))
                .toList();
        }
    }

    public static final class EventTraceSession {

        private final PaperLiveDebugger debugger;
        private final Event event;
        private final long traceId;
        private final ActivitySpanScope activity;
        private final PaperLiveDebugException.EventSnapshot eventBefore;
        private final List<PaperLiveEventTrace.HandlerExecution> handlers = new ArrayList<>();
        private final long startedAtEpochMillis = System.currentTimeMillis();
        private final long startedAtNanos = System.nanoTime();
        private boolean completed;

        private EventTraceSession(PaperLiveDebugger debugger, Event event, long traceId, ActivitySpanScope activity, PaperLiveDebugException.EventSnapshot eventBefore) {
            this.debugger = debugger;
            this.event = event;
            this.traceId = traceId;
            this.activity = activity;
            this.eventBefore = eventBefore;
        }

        public void record(int index, RegisteredListener listener, String status, long durationNanos, @Nullable Boolean cancelledBefore, @Nullable Boolean cancelledAfter) {
            try {
                this.handlers.add(new PaperLiveEventTrace.HandlerExecution(
                    index,
                    handlerSnapshot(index, listener),
                    status,
                    durationNanos,
                    cancelledBefore,
                    cancelledAfter
                ));
            } catch (Throwable ignored) {
                // Tracing must never affect a plugin handler.
            }
        }

        public void complete() {
            if (this.completed) {
                return;
            }
            this.completed = true;
            try {
                long duration = System.nanoTime() - this.startedAtNanos;
                PaperLiveEventTrace trace = new PaperLiveEventTrace(
                    this.debugger.nextSequence.incrementAndGet(),
                    this.startedAtEpochMillis,
                    this.traceId,
                    this.eventBefore,
                    PaperLiveEventContext.snapshot(this.event),
                    duration,
                    this.handlers
                );
                this.debugger.add(trace);
                this.debugger.publish(trace);
            } catch (Throwable ignored) {
                // Tracing must never affect event dispatch.
            } finally {
                this.activity.close();
            }
        }
    }

    public static final class CommandTraceScope {

        private final PaperLiveDebugger debugger;
        private final CommandSender sender;
        private final Command command;
        private final String commandLine;
        private final String commandLabel;
        private final long traceId;
        private final long rootSpanId;
        private final @Nullable TraceContext previous;
        private final long startedAt = System.nanoTime();
        private boolean completed;

        private CommandTraceScope(PaperLiveDebugger debugger, CommandSender sender, Command command, String commandLine, String commandLabel, long traceId, long rootSpanId, @Nullable TraceContext previous) {
            this.debugger = debugger;
            this.sender = sender;
            this.command = command;
            this.commandLine = commandLine;
            this.commandLabel = commandLabel;
            this.traceId = traceId;
            this.rootSpanId = rootSpanId;
            this.previous = previous;
        }

        public void complete(boolean executorResult, @Nullable Throwable throwable) {
            if (this.completed) {
                return;
            }
            this.completed = true;
            this.debugger.restoreTrace(this.previous);
            this.debugger.captureCommand(
                this.sender,
                this.command,
                this.commandLine,
                this.commandLabel,
                System.nanoTime() - this.startedAt,
                executorResult,
                throwable,
                this.traceId,
                this.rootSpanId
            );
        }
    }

    public static final class ActivitySpanScope implements AutoCloseable {

        private final @Nullable PaperLiveDebugger debugger;
        private final long traceId;
        private final long spanId;
        private final long parentSpanId;
        private final String name;
        private final String category;
        private final Map<String, String> details;
        private final @Nullable TraceContext previous;
        private final long startedAtEpochMillis = System.currentTimeMillis();
        private final long startedAtNanos = System.nanoTime();
        private @Nullable String failureReason;

        private ActivitySpanScope(@Nullable PaperLiveDebugger debugger, long traceId, long spanId, long parentSpanId, String name, String category, Map<String, String> details, @Nullable TraceContext previous) {
            this.debugger = debugger;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.name = name;
            this.category = category;
            this.details = Map.copyOf(details);
            this.previous = previous;
        }

        private static ActivitySpanScope noop() {
            return new ActivitySpanScope(null, 0, 0, 0, "", "", Map.of(), null);
        }

        public void fail(String reason) {
            this.failureReason = reason;
        }

        @Override
        public void close() {
            if (this.debugger == null) {
                return;
            }
            this.debugger.restoreTrace(this.previous);
            this.debugger.addActivitySpan(new PaperLiveActivitySpan(
                this.traceId,
                this.spanId,
                this.parentSpanId,
                this.startedAtEpochMillis,
                System.nanoTime() - this.startedAtNanos,
                this.name,
                this.category,
                Thread.currentThread().getName(),
                this.failureReason == null,
                this.failureReason,
                this.details
            ));
        }
    }

    private static List<PaperLiveDebugException.HandlerSnapshot> handlerSnapshots(RegisteredListener[] listeners) {
        List<PaperLiveDebugException.HandlerSnapshot> snapshots = new ArrayList<>(listeners.length);
        for (int index = 0; index < listeners.length; index++) {
            RegisteredListener listener = listeners[index];
            snapshots.add(handlerSnapshot(index, listener));
        }
        return snapshots;
    }

    private static PaperLiveDebugException.HandlerSnapshot handlerSnapshot(int index, RegisteredListener listener) {
        return new PaperLiveDebugException.HandlerSnapshot(
            index,
            pluginSnapshot(listener.getPlugin()),
            listener.getListener().getClass().getName(),
            listener.getPriority().name(),
            listener.isIgnoringCancelled()
        );
    }

    private static PaperLiveDebugException.PluginSnapshot pluginSnapshot(Plugin plugin) {
        return new PaperLiveDebugException.PluginSnapshot(
            plugin.getPluginMeta().getName(),
            plugin.getPluginMeta().getVersion(),
            plugin.isEnabled()
        );
    }

    private static PaperLiveDebugException.ThrowableSnapshot throwableSnapshot(Throwable throwable, int depth) {
        List<PaperLiveDebugException.StackFrameSnapshot> frames = Arrays.stream(throwable.getStackTrace())
            .limit(MAX_FRAMES_PER_THROWABLE)
            .map(frame -> new PaperLiveDebugException.StackFrameSnapshot(
                frame.getClassName(),
                frame.getMethodName(),
                frame.getFileName(),
                frame.getLineNumber()
            ))
            .toList();
        Throwable cause = throwable.getCause();
        PaperLiveDebugException.@Nullable ThrowableSnapshot causeSnapshot = cause == null || depth >= MAX_CAUSE_DEPTH
            ? null
            : throwableSnapshot(cause, depth + 1);
        return new PaperLiveDebugException.ThrowableSnapshot(
            throwable.getClass().getName(),
            throwable.getMessage(),
            frames,
            causeSnapshot
        );
    }

    private static String sanitizeCommandLine(String commandLine, String commandLabel) {
        return switch (commandLabel.toLowerCase(java.util.Locale.ROOT)) {
            case "login", "l", "register", "reg", "changepassword", "2fa", "totp" -> commandLabel + " <redacted>";
            default -> commandLine;
        };
    }
}
