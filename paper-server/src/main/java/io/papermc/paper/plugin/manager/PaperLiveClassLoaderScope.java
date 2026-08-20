package io.papermc.paper.plugin.manager;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Temporarily marks the current callback thread with its owning plugin classloader.
 *
 * <p>Child threads and executor workers inherit this context, allowing PaperLive to identify
 * otherwise idle plugin-owned runtime resources during a refresh.</p>
 */
public final class PaperLiveClassLoaderScope implements AutoCloseable {

    private final Thread thread;
    private final ClassLoader previousClassLoader;

    private PaperLiveClassLoaderScope(@NotNull Plugin plugin) {
        this.thread = Thread.currentThread();
        this.previousClassLoader = this.thread.getContextClassLoader();
        this.thread.setContextClassLoader(plugin.getClass().getClassLoader());
    }

    /**
     * Opens a context-classloader scope for one plugin callback.
     *
     * @param plugin plugin receiving the callback
     * @return a scope that restores the previous classloader when closed
     */
    public static @NotNull PaperLiveClassLoaderScope open(@NotNull Plugin plugin) {
        return new PaperLiveClassLoaderScope(plugin);
    }

    @Override
    public void close() {
        this.thread.setContextClassLoader(this.previousClassLoader);
    }
}
