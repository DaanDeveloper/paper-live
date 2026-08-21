package io.papermc.paper.plugin.manager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.misc.Unsafe;

/**
 * Stops raw threads that are still executing code owned by a plugin classloader.
 */
final class PaperLiveThreadQuiescer {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration COOPERATIVE_STOP_TIMEOUT = Duration.ofMillis(250);
    private static final Unsafe UNSAFE = loadUnsafe();

    private PaperLiveThreadQuiescer() {
    }

    /**
     * Interrupts plugin-owned threads and waits a bounded amount of time for cooperative shutdown.
     *
     * @param plugin the plugin being prepared for reload
     * @return diagnostics for threads that did not stop safely
     */
    static @NotNull List<ThreadBlocker> stopOwnedThreads(@NotNull Plugin plugin) {
        ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
        List<Thread> ownedThreads = new ArrayList<>();

        for (Thread thread : Thread.getAllStackTraces().keySet()) {

            if (thread == Thread.currentThread() || !thread.isAlive()) {
                continue;
            }

            if (isOwnedBy(pluginClassLoader, thread)) {
                ownedThreads.add(thread);
            }
        }

        for (Thread thread : ownedThreads) {
            thread.interrupt();
        }

        waitForThreads(ownedThreads, COOPERATIVE_STOP_TIMEOUT);

        for (Thread thread : ownedThreads) {
            if (thread.isAlive()) {
                stopBackingExecutor(thread);
                thread.interrupt();
            }
        }

        waitForThreads(ownedThreads, STOP_TIMEOUT);

        return ownedThreads.stream()
            .filter(Thread::isAlive)
            .map(thread -> new ThreadBlocker(thread.getName(), thread.getState(), List.of(thread.getStackTrace())))
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name()))
            .toList();
    }

    private static void waitForThreads(@NotNull List<Thread> threads, @NotNull Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();

        for (Thread thread : threads) {
            long remainingNanos = deadline - System.nanoTime();

            if (remainingNanos <= 0L) {
                break;
            }

            try {
                thread.join(Math.max(1L, Duration.ofNanos(remainingNanos).toMillis()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void stopBackingExecutor(@NotNull Thread thread) {
        Object holder = readField(thread, "holder");
        Object task = holder == null ? readField(thread, "target") : readField(holder, "task");
        Object executor = readField(task, "this$0");

        if (!(executor instanceof ExecutorService executorService)) {
            return;
        }

        if (executor instanceof ScheduledThreadPoolExecutor scheduledExecutor) {
            closeHikariPools(scheduledExecutor);
        }

        executorService.shutdownNow();
    }

    private static void closeHikariPools(@NotNull ScheduledThreadPoolExecutor executor) {
        for (Runnable queuedTask : executor.getQueue()) {
            Object housekeeper = findNestedObject(
                queuedTask,
                "com.zaxxer.hikari.pool.HikariPool$HouseKeeper",
                Collections.newSetFromMap(new IdentityHashMap<>()),
                0
            );

            if (housekeeper == null) {
                continue;
            }

            Object hikariPool = readField(housekeeper, "this$0");
            if (hikariPool == null) {
                continue;
            }

            try {
                hikariPool.getClass().getMethod("shutdown").invoke(hikariPool);
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                // A different Hikari version may not expose the pool shutdown method.
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static @Nullable Object findNestedObject(@Nullable Object value, @NotNull String className, @NotNull Set<Object> visited, int depth) {
        if (value == null || depth > 8 || !visited.add(value)) {
            return null;
        }
        if (value.getClass().getName().equals(className)) {
            return value;
        }

        for (String fieldName : List.of("callable", "task", "outerTask", "runnable")) {
            Object nested = findNestedObject(readField(value, fieldName), className, visited, depth + 1);
            if (nested != null) {
                return nested;
            }
        }

        return null;
    }

    private static @Nullable Object readField(@Nullable Object owner, @NotNull String fieldName) {
        if (owner == null || UNSAFE == null) {
            return null;
        }

        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                if (field.getType().isPrimitive()) {
                    return null;
                }
                return UNSAFE.getObject(owner, UNSAFE.objectFieldOffset(field));
            } catch (NoSuchFieldException ignored) {
            }
        }

        return null;
    }

    private static @Nullable Unsafe loadUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean isOwnedBy(@NotNull ClassLoader pluginClassLoader, @NotNull Thread thread) {
        // Do not resolve stack-frame classes through the plugin classloader here. During a
        // refresh that classloader may already have closed its JAR, which turns diagnostic
        // probing into a "zip file closed" failure on the server thread.
        return thread.getContextClassLoader() == pluginClassLoader;
    }

    /**
     * Details about a plugin thread that did not stop during a refresh.
     *
     * @param name the thread name
     * @param state the state observed after the shutdown timeout
     * @param stackTrace the stack trace observed after the shutdown timeout
     */
    record ThreadBlocker(@NotNull String name, @NotNull Thread.State state, @NotNull List<StackTraceElement> stackTrace) {

        @NotNull String summary() {
            return this.name + " [" + this.state + "]";
        }

        @NotNull String diagnostic() {
            StringBuilder diagnostic = new StringBuilder(this.summary());

            for (StackTraceElement stackFrame : this.stackTrace) {
                diagnostic.append("\n    at ").append(stackFrame);
            }

            return diagnostic.toString();
        }
    }
}
