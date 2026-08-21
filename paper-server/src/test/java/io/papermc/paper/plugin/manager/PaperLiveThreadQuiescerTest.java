package io.papermc.paper.plugin.manager;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperLiveThreadQuiescerTest {

    @Test
    void stopsExecutorBackingPluginOwnedThread() throws Exception {
        try (URLClassLoader pluginClassLoader = new URLClassLoader(new URL[0], Plugin.class.getClassLoader())) {
            Plugin plugin = (Plugin) Proxy.newProxyInstance(pluginClassLoader, new Class<?>[]{Plugin.class}, (proxy, method, arguments) -> null);
            CountDownLatch started = new CountDownLatch(1);
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "PaperLive quiescer test");
                thread.setContextClassLoader(plugin.getClass().getClassLoader());
                return thread;
            });

            try {
                executor.scheduleAtFixedRate(started::countDown, 0L, 1L, TimeUnit.HOURS);
                assertTrue(started.await(5L, TimeUnit.SECONDS));
                assertTrue(PaperLiveThreadQuiescer.stopOwnedThreads(plugin).isEmpty());
                assertTrue(executor.isShutdown());
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
