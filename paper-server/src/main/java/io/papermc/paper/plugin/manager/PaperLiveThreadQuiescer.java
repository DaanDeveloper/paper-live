package io.papermc.paper.plugin.manager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Stops raw threads that are still executing code owned by a plugin classloader.
 */
final class PaperLiveThreadQuiescer {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);

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
        Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
        List<Thread> ownedThreads = new ArrayList<>();

        for (Map.Entry<Thread, StackTraceElement[]> entry : allStackTraces.entrySet()) {
            Thread thread = entry.getKey();

            if (thread == Thread.currentThread() || !thread.isAlive()) {
                continue;
            }

            if (isOwnedBy(pluginClassLoader, thread, entry.getValue())) {
                ownedThreads.add(thread);
            }
        }

        for (Thread thread : ownedThreads) {
            thread.interrupt();
        }

        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();

        for (Thread thread : ownedThreads) {
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

        return ownedThreads.stream()
            .filter(Thread::isAlive)
            .map(thread -> new ThreadBlocker(thread.getName(), thread.getState(), List.of(thread.getStackTrace())))
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name()))
            .toList();
    }

    static boolean isOwnedBy(@NotNull ClassLoader pluginClassLoader, @NotNull Thread thread, @NotNull StackTraceElement[] stackTrace) {
        if (thread.getContextClassLoader() == pluginClassLoader) {
            return true;
        }

        for (StackTraceElement stackFrame : stackTrace) {
            try {
                Class<?> frameClass = Class.forName(stackFrame.getClassName(), false, pluginClassLoader);

                if (frameClass.getClassLoader() == pluginClassLoader) {
                    return true;
                }
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }

        return false;
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
