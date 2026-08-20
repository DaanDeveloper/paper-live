package io.papermc.paper.plugin.manager;

import java.net.URL;
import java.net.URLClassLoader;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("Normal")
class PaperLiveClassLoaderScopeTest {

    @Test
    void restoresThePreviousContextClassLoader() throws Exception {
        Thread currentThread = Thread.currentThread();
        ClassLoader previousClassLoader = currentThread.getContextClassLoader();

        try (URLClassLoader pluginClassLoader = new URLClassLoader(new URL[0], previousClassLoader)) {
            Plugin loadedByPluginClassLoader = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                pluginClassLoader,
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> method.getReturnType().isPrimitive() ? primitiveDefault(method.getReturnType()) : null
            );

            try (PaperLiveClassLoaderScope ignored = PaperLiveClassLoaderScope.open(loadedByPluginClassLoader)) {
                assertSame(pluginClassLoader, currentThread.getContextClassLoader());
            }

            assertSame(previousClassLoader, currentThread.getContextClassLoader());
        }
    }

    private static Object primitiveDefault(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }

        if (primitiveType == char.class) {
            return '\0';
        }

        return 0;
    }
}
