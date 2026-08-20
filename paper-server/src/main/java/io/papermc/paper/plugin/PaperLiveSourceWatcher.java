package io.papermc.paper.plugin;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Watches PaperLive project sources and requests a debounced refresh after changes.
 */
public final class PaperLiveSourceWatcher implements Runnable {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(30);
    private static final Set<String> IGNORED_DIRECTORIES = Set.of("build", "target", ".gradle", ".git", ".idea", "out");
    private static PaperLiveSourceWatcher instance;

    private final Path projectsDirectory;
    private final WatchService watchService;
    private final Duration debounce;
    private final Map<WatchKey, Path> directoriesByKey = new HashMap<>();
    private long lastRelevantChange;

    private PaperLiveSourceWatcher(@NotNull Path projectsDirectory, @NotNull Duration debounce) throws IOException {
        this.projectsDirectory = projectsDirectory;
        this.debounce = debounce;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.registerRecursively(projectsDirectory);
    }

    /**
     * Starts the single source watcher after plugins have been enabled.
     *
     * @param pluginDirectory Paper's configured plugin directory
     */
    public static synchronized void start(@NotNull Path pluginDirectory) {
        if (instance != null) {
            return;
        }

        AutomaticRefreshConfiguration configuration = loadAutomaticRefreshConfiguration(pluginDirectory);

        if (!configuration.enabled()) {
            LOGGER.info("[PaperLive] Automatic source refresh is disabled. Use /paperlive refresh to rebuild projects.");
            return;
        }

        Path projectsDirectory = pluginDirectory.resolve("PaperLive").resolve("projects");

        try {
            Files.createDirectories(projectsDirectory);
            instance = new PaperLiveSourceWatcher(projectsDirectory, configuration.debounce());

            Thread watcherThread = new Thread(instance, "PaperLive Source Watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            LOGGER.info("[PaperLive] Watching source projects in {} with a {}-second debounce", projectsDirectory.toAbsolutePath(), configuration.debounce().toSeconds());
        } catch (IOException exception) {
            LOGGER.error("[PaperLive] Cannot start the source watcher for {}", projectsDirectory, exception);
        }
    }

    private static @NotNull AutomaticRefreshConfiguration loadAutomaticRefreshConfiguration(@NotNull Path pluginDirectory) {
        Path configurationFile = pluginDirectory.resolve("PaperLive").resolve("config.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configurationFile.toFile());

        if (!Files.exists(configurationFile)) {
            configuration.set("auto-refresh", true);
            configuration.set("auto-refresh-debounce-seconds", DEFAULT_DEBOUNCE.toSeconds());

            try {
                Files.createDirectories(configurationFile.getParent());
                configuration.save(configurationFile.toFile());
            } catch (IOException exception) {
                LOGGER.error("[PaperLive] Cannot create configuration file {}", configurationFile, exception);
            }
        }

        int debounceSeconds = Math.max(1, configuration.getInt("auto-refresh-debounce-seconds", (int) DEFAULT_DEBOUNCE.toSeconds()));
        return new AutomaticRefreshConfiguration(configuration.getBoolean("auto-refresh", true), Duration.ofSeconds(debounceSeconds));
    }

    @Override
    public void run() {
        while (!MinecraftServer.getServer().isStopped()) {
            try {
                WatchKey key = this.watchService.poll(250, TimeUnit.MILLISECONDS);

                if (key != null) {
                    this.process(key);
                }

                this.refreshAfterDebounce();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable throwable) {
                LOGGER.error("[PaperLive] Source watcher failed", throwable);
            }
        }

        try {
            this.watchService.close();
        } catch (IOException exception) {
            LOGGER.debug("[PaperLive] Could not close the source watcher", exception);
        }
    }

    private void process(@NotNull WatchKey key) {
        Path directory = this.directoriesByKey.get(key);

        if (directory == null) {
            key.cancel();
            return;
        }

        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                this.lastRelevantChange = System.nanoTime();
                continue;
            }

            Path changedPath = directory.resolve((Path) event.context());

            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changedPath)) {
                this.registerRecursively(changedPath);
            }

            if (isRelevantPath(this.projectsDirectory, changedPath, Files.isDirectory(changedPath))) {
                this.lastRelevantChange = System.nanoTime();
            }
        }

        if (!key.reset()) {
            this.directoriesByKey.remove(key);
        }
    }

    private void refreshAfterDebounce() {
        if (this.lastRelevantChange == 0L) {
            return;
        }

        long elapsed = System.nanoTime() - this.lastRelevantChange;

        if (elapsed < this.debounce.toNanos()) {
            return;
        }

        this.lastRelevantChange = 0L;
        PaperLiveRefreshService.requestRefresh("source change detected");
    }

    static boolean isRelevantPath(@NotNull Path projectsDirectory, @NotNull Path changedPath, boolean directory) {
        Path relativePath;

        try {
            relativePath = projectsDirectory.relativize(changedPath);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        for (Path part : relativePath) {
            if (IGNORED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        if (directory) {
            return true;
        }

        String fileName = changedPath.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java")
            || fileName.endsWith(".kt")
            || fileName.endsWith(".gradle")
            || fileName.endsWith(".kts")
            || fileName.endsWith(".yml")
            || fileName.endsWith(".yaml")
            || fileName.endsWith(".json")
            || fileName.endsWith(".properties")
            || fileName.endsWith(".xml")
            || fileName.equals("gradlew")
            || fileName.equals("gradlew.bat")
            || fileName.equals("mvnw")
            || fileName.equals("mvnw.cmd");
    }

    private void registerRecursively(@NotNull Path root) {
        try (Stream<Path> directories = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            directories
                .filter(Files::isDirectory)
                .filter(this::isWatchableDirectory)
                .forEach(this::register);
        } catch (IOException exception) {
            LOGGER.error("[PaperLive] Cannot watch source directory {}", root, exception);
        }
    }

    private boolean isWatchableDirectory(@NotNull Path directory) {
        Path fileName = directory.getFileName();

        if (fileName == null) {
            return true;
        }

        return !IGNORED_DIRECTORIES.contains(fileName.toString().toLowerCase(Locale.ROOT));
    }

    private void register(@NotNull Path directory) {
        try {
            WatchKey key = directory.register(
                this.watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            );
            this.directoriesByKey.put(key, directory);
        } catch (IOException exception) {
            LOGGER.error("[PaperLive] Cannot watch source directory {}", directory, exception);
        }
    }

    private record AutomaticRefreshConfiguration(boolean enabled, @NotNull Duration debounce) {
    }
}
