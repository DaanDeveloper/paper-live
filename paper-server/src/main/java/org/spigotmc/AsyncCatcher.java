package org.spigotmc;

import net.minecraft.server.MinecraftServer;

public class AsyncCatcher {

    public static void catchOp(String reason) {
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) { // Paper - chunk system
            Throwable stack = new Throwable();
            io.papermc.paper.plugin.debug.PaperLiveDebugger.instance().captureAsyncViolation(reason, stack);
            MinecraftServer.LOGGER.error("Thread {} failed main thread check: {}", Thread.currentThread().getName(), reason, stack); // Paper
            throw new IllegalStateException("Asynchronous " + reason + "!");
        }
    }
}
