package io.papermc.paper.plugin;

import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** Creates and restores consistent dev-world plus source-plugin-data snapshots. */
public final class PaperLiveDevSnapshots {

    private PaperLiveDevSnapshots() {
    }

    public static void create(@NotNull Consumer<String> output, @NotNull String snapshotName, @NotNull String worldName) {
        validateName(snapshotName);
        World world = requireWorld(worldName);
        PluginInitializerManager initializer = requireInitializer();
        SnapshotContext context = context(initializer.pluginDirectoryPath(), world);
        Path snapshot = snapshotPath(snapshotName);

        withQuiescedSourcePlugins(output, context, () -> {
            world.save();
            PaperLiveSnapshotStore.create(snapshot, context.targets());
            output.accept("§a[PaperLive] Snapshot '" + snapshotName + "' saved: §f" + snapshot);
            output.accept("§7[PaperLive] Captured world playerdata and " + (context.targets().size() - 1) + " source-plugin data folder(s).");
        });
    }

    public static void restore(@NotNull Consumer<String> output, @NotNull String snapshotName, @NotNull String worldName) {
        validateName(snapshotName);
        World world = requireWorld(worldName);
        World primary = Bukkit.getWorlds().getFirst();
        if (world.getName().equalsIgnoreCase(primary.getName())) {
            throw new IllegalArgumentException("The primary world cannot be restored live; use a disposable dev world.");
        }
        PluginInitializerManager initializer = requireInitializer();
        SnapshotContext context = context(initializer.pluginDirectoryPath(), world);
        Path snapshot = snapshotPath(snapshotName);
        if (!Files.isRegularFile(snapshot)) {
            throw new IllegalArgumentException("Snapshot '" + snapshotName + "' does not exist.");
        }

        withQuiescedSourcePlugins(output, context, () -> {
            Location fallback = primary.getSpawnLocation();
            for (Player player : List.copyOf(world.getPlayers())) {
                if (!player.teleport(fallback)) {
                    throw new IOException("Could not move player '" + player.getName() + "' out of the snapshot world");
                }
            }
            if (!Bukkit.unloadWorld(world, false)) {
                throw new IOException("Paper refused to unload world '" + worldName + "'");
            }
            PaperLiveSnapshotStore.RestoreResult result = PaperLiveSnapshotStore.restore(
                snapshot,
                context.targets(),
                Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().resolve(".paperlive-snapshot-recovery")
            );
            World restored = new WorldCreator(worldName).createWorld();
            if (restored == null) {
                throw new IOException("Paper could not reload restored world '" + worldName + "'");
            }
            output.accept("§a[PaperLive] Snapshot '" + snapshotName + "' restored for world '" + worldName + "'.");
            output.accept("§e[PaperLive] Pre-restore recovery copy: §f" + result.recoveryDirectory());
        });
    }

    public static @NotNull List<String> list() {
        Path root = snapshotRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".zip"))
                .map(name -> name.substring(0, name.length() - 4))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public static @NotNull List<String> sourcePluginNames() {
        PluginInitializerManager initializer = requireInitializer();
        return runtimeJars(initializer.pluginDirectoryPath()).stream()
            .map(PaperLiveProjectCompiler::findPluginName)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private static SnapshotContext context(Path pluginDirectory, World world) {
        List<Path> runtimeJars = runtimeJars(pluginDirectory);
        List<String> sourcePluginNames = runtimeJars.stream()
            .map(PaperLiveProjectCompiler::findPluginName)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        List<PaperLiveSnapshotStore.Target> targets = new ArrayList<>();
        targets.add(new PaperLiveSnapshotStore.Target("world-" + safeLabel(world.getName()), world.getWorldPath()));
        for (String pluginName : sourcePluginNames) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            Path dataFolder = plugin == null ? pluginDirectory.resolve(pluginName) : plugin.getDataFolder().toPath();
            try {
                Files.createDirectories(dataFolder);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not prepare plugin data folder for '" + pluginName + "'", exception);
            }
            targets.add(new PaperLiveSnapshotStore.Target("plugin-" + safeLabel(pluginName), dataFolder));
        }
        return new SnapshotContext(runtimeJars, sourcePluginNames, List.copyOf(targets));
    }

    private static void withQuiescedSourcePlugins(Consumer<String> output, SnapshotContext context, SnapshotOperation operation) {
        PaperPluginManagerImpl pluginManager = PaperPluginManagerImpl.getInstance();
        PaperPluginManagerImpl.PaperLiveRefreshResult preparation = pluginManager.preparePaperLiveRefresh(context.sourcePluginNames());
        if (!preparation.successful()) {
            throw new IllegalArgumentException("Snapshot blocked by active plugin resources: " + String.join(", ", preparation.blockers()));
        }
        try {
            operation.run();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Snapshot operation failed: " + exception.getMessage(), exception);
        } finally {
            try {
                pluginManager.loadPaperLivePlugins(context.runtimeJars());
            } catch (Throwable failure) {
                output.accept("§c[PaperLive] Snapshot finished, but source plugins could not be reloaded: " + failure.getMessage());
            }
        }
    }

    private static List<Path> runtimeJars(Path pluginDirectory) {
        Path runtime = pluginDirectory.resolve(".paperlive-runtime");
        if (!Files.isDirectory(runtime)) {
            return List.of();
        }
        try (var files = Files.list(runtime)) {
            return files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("paperlive-"))
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static World requireWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new IllegalArgumentException("World '" + name + "' is not loaded.");
        }
        return world;
    }

    private static PluginInitializerManager requireInitializer() {
        PluginInitializerManager initializer = PluginInitializerManager.instance();
        if (initializer == null) {
            throw new IllegalArgumentException("PaperLive project discovery is not ready.");
        }
        return initializer;
    }

    private static Path snapshotPath(String name) {
        return snapshotRoot().resolve(name + ".zip").normalize();
    }

    private static Path snapshotRoot() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().resolve(".paperlive-dev-snapshots");
    }

    private static void validateName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Snapshot name must use 1-64 letters, numbers, '.', '_' or '-'.");
        }
    }

    private static String safeLabel(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record SnapshotContext(List<Path> runtimeJars, List<String> sourcePluginNames, List<PaperLiveSnapshotStore.Target> targets) {
    }

    @FunctionalInterface
    private interface SnapshotOperation {
        void run() throws IOException;
    }
}
