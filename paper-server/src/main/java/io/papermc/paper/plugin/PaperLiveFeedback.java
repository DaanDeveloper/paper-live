package io.papermc.paper.plugin;

import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Delivers PaperLive lifecycle feedback to the console and online administrators.
 */
final class PaperLiveFeedback {

    static final String PERMISSION = "paperlive.command";

    private PaperLiveFeedback() {
    }

    static void info(@NotNull String message) {
        broadcast("§e[PaperLive] §f" + message);
    }

    static void success(@NotNull String message) {
        broadcast("§a[PaperLive] " + message);
    }

    static void error(@NotNull String message) {
        broadcast("§c[PaperLive] " + message);
    }

    private static void broadcast(@NotNull String message) {
        MinecraftServer server = MinecraftServer.getServer();

        if (server == null) {
            return;
        }

        if (!server.isSameThread()) {
            server.execute(() -> broadcast(message));
            return;
        }

        Bukkit.getConsoleSender().sendMessage(message);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION)) {
                player.sendMessage(message);
            }
        }
    }
}
