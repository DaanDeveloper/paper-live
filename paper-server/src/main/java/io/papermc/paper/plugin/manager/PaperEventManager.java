package io.papermc.paper.plugin.manager;

import co.aikar.timings.TimedEventExecutor;
import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerEventException;
import com.google.common.collect.Sets;
import io.papermc.paper.plugin.debug.PaperLiveDebugger;
import org.bukkit.Server;
import org.bukkit.Warning;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.Cancellable;
import org.bukkit.plugin.AuthorNagException;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

class PaperEventManager {

    private final Server server;
    private final PaperLiveRuntimeRegistry paperLiveRuntimeRegistry;
    private final PaperLiveDebugger paperLiveDebugger;

    public PaperEventManager(@NotNull Server server, @NotNull PaperLiveRuntimeRegistry paperLiveRuntimeRegistry, @NotNull PaperLiveDebugger paperLiveDebugger) {
        this.server = server;
        this.paperLiveRuntimeRegistry = paperLiveRuntimeRegistry;
        this.paperLiveDebugger = paperLiveDebugger;
    }

    // SimplePluginManager
    public void callEvent(@NotNull Event event) {
        if (event.isAsynchronous() && this.server.isPrimaryThread()) {
            throw new IllegalStateException(event.getEventName() + " may only be triggered asynchronously.");
        } else if (!event.isAsynchronous() && !this.server.isPrimaryThread() && !this.server.isStopping()) {
            throw new IllegalStateException(event.getEventName() + " may only be triggered synchronously.");
        }

        HandlerList handlers = event.getHandlers();
        RegisteredListener[] listeners = handlers.getRegisteredListeners();
        this.paperLiveDebugger.observeEvent(event);
        PaperLiveDebugger.EventTraceSession eventTrace = this.paperLiveDebugger.beginEventTrace(event, listeners);

        try {
            for (int handlerIndex = 0; handlerIndex < listeners.length; handlerIndex++) {
                RegisteredListener registration = listeners[handlerIndex];
                Boolean cancelledBefore = cancellationState(event);
                if (!registration.getPlugin().isEnabled()) {
                    recordEventHandler(eventTrace, handlerIndex, registration, "SKIPPED_DISABLED", 0L, cancelledBefore, cancelledBefore);
                    continue;
                }

                long startedAt = eventTrace == null ? 0L : System.nanoTime();
                try (PaperLiveClassLoaderScope ignored = PaperLiveClassLoaderScope.open(registration.getPlugin())) {
                    registration.callEvent(event);
                    String status = Boolean.TRUE.equals(cancelledBefore) && registration.isIgnoringCancelled() ? "SKIPPED_CANCELLED" : "COMPLETED";
                    recordEventHandler(eventTrace, handlerIndex, registration, status, elapsedSince(startedAt), cancelledBefore, cancellationState(event));
                } catch (AuthorNagException ex) {
                    recordEventHandler(eventTrace, handlerIndex, registration, "AUTHOR_NAG", elapsedSince(startedAt), cancelledBefore, cancellationState(event));
                    Plugin plugin = registration.getPlugin();

                    if (plugin.isNaggable()) {
                        plugin.setNaggable(false);

                        this.server.getLogger().log(Level.SEVERE, String.format(
                            "Nag author(s): '%s' of '%s' about the following: %s",
                            plugin.getPluginMeta().getAuthors(),
                            plugin.getPluginMeta().getDisplayName(),
                            ex.getMessage()
                        ));
                    }
                } catch (Throwable ex) {
                    recordEventHandler(eventTrace, handlerIndex, registration, "FAILED", elapsedSince(startedAt), cancelledBefore, cancellationState(event));
                    this.paperLiveDebugger.captureEventException(event, listeners, handlerIndex, ex);
                    String msg = "Could not pass event " + event.getEventName() + " to " + registration.getPlugin().getPluginMeta().getDisplayName();
                    this.server.getLogger().log(Level.SEVERE, msg, ex);
                    if (!(event instanceof ServerExceptionEvent)) { // We don't want to cause an endless event loop
                        this.callEvent(new ServerExceptionEvent(new ServerEventException(msg, ex, registration.getPlugin(), registration.getListener(), event)));
                    }
                }
            }
        } finally {
            if (eventTrace != null) {
                eventTrace.complete();
            }
        }
    }

    private static long elapsedSince(long startedAt) {
        return startedAt == 0L ? 0L : System.nanoTime() - startedAt;
    }

    private static @Nullable Boolean cancellationState(Event event) {
        try {
            return event instanceof Cancellable cancellable ? cancellable.isCancelled() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void recordEventHandler(
        @Nullable PaperLiveDebugger.EventTraceSession eventTrace,
        int handlerIndex,
        RegisteredListener registration,
        String status,
        long durationNanos,
        @Nullable Boolean cancelledBefore,
        @Nullable Boolean cancelledAfter
    ) {
        if (eventTrace != null) {
            eventTrace.record(handlerIndex, registration, status, durationNanos, cancelledBefore, cancelledAfter);
        }
    }

    public void registerEvents(@NotNull Listener listener, @NotNull Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + listener + " while not enabled");
        }

        Map<Class<? extends Event>, Set<RegisteredListener>> registrations = this.createRegisteredListeners(listener, plugin);
        int registrationCount = 0;

        for (Map.Entry<Class<? extends Event>, Set<RegisteredListener>> entry : registrations.entrySet()) {
            this.getEventListeners(this.getRegistrationClass(entry.getKey())).registerAll(entry.getValue());
            this.paperLiveDebugger.recordEventRegistrations(entry.getKey(), entry.getValue());
            registrationCount += entry.getValue().size();
        }

        this.paperLiveRuntimeRegistry.recordEventRegistrations(plugin, registrationCount);
    }

    public void registerEvent(@NotNull Class<? extends Event> event, @NotNull Listener listener, @NotNull EventPriority priority, @NotNull EventExecutor executor, @NotNull Plugin plugin) {
        this.registerEvent(event, listener, priority, executor, plugin, false);
    }

    public void registerEvent(@NotNull Class<? extends Event> event, @NotNull Listener listener, @NotNull EventPriority priority, @NotNull EventExecutor executor, @NotNull Plugin plugin, boolean ignoreCancelled) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + event + " while not enabled");
        }

        RegisteredListener registration = new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled);
        this.getEventListeners(event).register(registration);
        this.paperLiveDebugger.recordEventRegistrations(event, List.of(registration));
        this.paperLiveRuntimeRegistry.recordEventRegistrations(plugin, 1);
    }

    @NotNull
    private HandlerList getEventListeners(@NotNull Class<? extends Event> type) {
        try {
            Method method = this.getRegistrationClass(type).getDeclaredMethod("getHandlerList");
            method.setAccessible(true);
            return (HandlerList) method.invoke(null);
        } catch (Exception e) {
            throw new IllegalPluginAccessException(e.toString());
        }
    }

    @NotNull
    private Class<? extends Event> getRegistrationClass(@NotNull Class<? extends Event> clazz) {
        try {
            clazz.getDeclaredMethod("getHandlerList");
            return clazz;
        } catch (NoSuchMethodException e) {
            if (clazz.getSuperclass() != null
                && !clazz.getSuperclass().equals(Event.class)
                && Event.class.isAssignableFrom(clazz.getSuperclass())) {
                return this.getRegistrationClass(clazz.getSuperclass().asSubclass(Event.class));
            } else {
                throw new IllegalPluginAccessException("Unable to find handler list for event " + clazz.getName() + ". Static getHandlerList method required!");
            }
        }
    }

    // JavaPluginLoader
    @NotNull
    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(@NotNull Listener listener, @NotNull final Plugin plugin) {
        Map<Class<? extends Event>, Set<RegisteredListener>> ret = new HashMap<>();

        Set<Method> methods;
        try {
            Class<?> listenerClazz = listener.getClass();
            methods = Sets.union(
                Set.of(listenerClazz.getMethods()),
                Set.of(listenerClazz.getDeclaredMethods())
            );
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe("Failed to register events for " + listener.getClass() + " because " + e.getMessage() + " does not exist.");
            return ret;
        }

        for (final Method method : methods) {
            final EventHandler eh = method.getAnnotation(EventHandler.class);
            if (eh == null) continue;
            // Do not register bridge or synthetic methods to avoid event duplication
            // Fixes SPIGOT-893
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            final Class<?> checkClass;
            if (method.getParameterTypes().length != 1 || !Event.class.isAssignableFrom(checkClass = method.getParameterTypes()[0])) {
                plugin.getLogger().severe(plugin.getPluginMeta().getDisplayName() + " attempted to register an invalid EventHandler method signature \"" + method.toGenericString() + "\" in " + listener.getClass());
                continue;
            }
            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
            method.setAccessible(true);
            Set<RegisteredListener> eventSet = ret.computeIfAbsent(eventClass, k -> new HashSet<>());

            for (Class<?> clazz = eventClass; Event.class.isAssignableFrom(clazz); clazz = clazz.getSuperclass()) {
                // This loop checks for extending deprecated events
                if (clazz.getAnnotation(Deprecated.class) != null) {
                    Warning warning = clazz.getAnnotation(Warning.class);
                    Warning.WarningState warningState = this.server.getWarningState();
                    if (!warningState.printFor(warning)) {
                        break;
                    }
                    plugin.getLogger().log(
                        Level.WARNING,
                        String.format(
                            "\"%s\" has registered a listener for %s on method \"%s\", but the event is Deprecated. \"%s\"; please notify the authors %s.",
                            plugin.getPluginMeta().getDisplayName(),
                            clazz.getName(),
                            method.toGenericString(),
                            (warning != null && warning.reason().length() != 0) ? warning.reason() : "Server performance will be affected",
                            Arrays.toString(plugin.getPluginMeta().getAuthors().toArray())),
                        warningState == Warning.WarningState.ON ? new AuthorNagException(null) : null);
                    break;
                }
            }

            EventExecutor executor = EventExecutor.create(method, eventClass);
            eventSet.add(new RegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
        }
        return ret;
    }

    public void clearEvents() {
        HandlerList.unregisterAll();
        this.paperLiveDebugger.clearEventRegistrations();
    }
}
