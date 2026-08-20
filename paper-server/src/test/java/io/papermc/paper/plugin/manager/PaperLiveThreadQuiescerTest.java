package io.papermc.paper.plugin.manager;

import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Normal")
class PaperLiveThreadQuiescerTest {

    @Test
    void recognizesAThreadWithThePluginContextClassLoader() throws Exception {
        try (URLClassLoader pluginClassLoader = new URLClassLoader(new URL[0], null)) {
            Thread thread = new Thread(() -> {
            });
            thread.setContextClassLoader(pluginClassLoader);

            assertTrue(PaperLiveThreadQuiescer.isOwnedBy(pluginClassLoader, thread, new StackTraceElement[0]));
        }
    }

    @Test
    void ignoresAnUnrelatedThread() throws Exception {
        try (URLClassLoader pluginClassLoader = new URLClassLoader(new URL[0], null)) {
            Thread thread = new Thread(() -> {
            });

            assertFalse(PaperLiveThreadQuiescer.isOwnedBy(pluginClassLoader, thread, new StackTraceElement[0]));
        }
    }
}
