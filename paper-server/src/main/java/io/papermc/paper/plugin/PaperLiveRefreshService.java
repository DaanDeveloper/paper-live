package io.papermc.paper.plugin;

import com.mojang.logging.LogUtils;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Coordinates asynchronous source compilation and main-thread plugin replacement.
 */
final class PaperLiveRefreshService {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean();
    private static final ExecutorService BUILD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PaperLive Project Builder");
        thread.setDaemon(true);
        return thread;
    });

    private PaperLiveRefreshService() {
    }

    /**
     * Requests a refresh. Concurrent requests are collapsed into one additional refresh.
     *
     * @param reason user-facing reason for the refresh
     */
    static void requestRefresh(@NotNull String reason) {
        if (!REFRESHING.compareAndSet(false, true)) {
            REFRESH_PENDING.set(true);
            PaperLiveFeedback.info("Another change was detected; one extra refresh is queued.");
            return;
        }

        PaperLiveFeedback.info("Compiling source projects: " + reason + ".");
        BUILD_EXECUTOR.execute(PaperLiveRefreshService::compileAndRefresh);
    }

    /** Builds one source project and loads its resulting Bukkit plugin into the live runtime. */
    static void requestLoad(@NotNull String projectName, boolean includeDependencies) {
        if (!REFRESHING.compareAndSet(false, true)) {
            PaperLiveFeedback.error("A PaperLive build or refresh is already running.");
            return;
        }

        PluginInitializerManager initializerManager = PluginInitializerManager.instance();
        if (initializerManager == null) {
            PaperLiveFeedback.error("Cannot load because project discovery is not initialized.");
            complete();
            return;
        }

        Path pluginDirectory = initializerManager.pluginDirectoryPath();
        boolean sourceProject = PaperLiveProjectCompiler.findProjectDirectories(pluginDirectory.resolve("PaperLive").resolve("projects")).stream()
            .anyMatch(project -> project.getFileName().toString().equalsIgnoreCase(projectName));
        if (sourceProject) {
            PaperLiveFeedback.info("Compiling source project '" + projectName + "' for loading.");
            BUILD_EXECUTOR.execute(() -> compileAndLoad(projectName, includeDependencies));
            return;
        }

        Path pluginJar = findPluginJar(pluginDirectory, projectName);
        if (pluginJar == null) {
            PaperLiveFeedback.error("No source project or plugin JAR named '" + projectName + "' was found.");
            complete();
            return;
        }

        PaperLiveFeedback.info("Loading Bukkit plugin JAR '" + pluginJar.getFileName() + "'.");
        MinecraftServer.getServer().execute(() -> loadBukkitPlugin(pluginJar, includeDependencies));
    }

    /** Removes a PaperLive-managed Bukkit plugin, including its commands and classloader. */
    static void requestUnload(@NotNull String pluginName, boolean includeDependents) {
        if (REFRESHING.get()) {
            PaperLiveFeedback.error("Cannot unload while a PaperLive build or refresh is running.");
            return;
        }

        MinecraftServer.getServer().execute(() -> {
            PaperPluginManagerImpl.PluginUnloadResult result = PaperPluginManagerImpl.getInstance().unloadPlugin(pluginName, includeDependents);
            if (!result.found()) {
                PaperLiveFeedback.error("No loaded Bukkit plugin named '" + pluginName + "' was found.");
            } else if (!includeDependents && !result.dependents().isEmpty()) {
                PaperLiveFeedback.error("'" + pluginName + "' is used by: " + String.join(", ", result.dependents()) + ". It was left loaded.");
                PaperLiveFeedback.info("To also unload those plugins, run: /unload " + pluginName + " dependents");
            } else if (!result.successful()) {
                PaperLiveFeedback.error("Could not unload '" + pluginName + "'; its runtime resources were restored.");
                for (String blocker : result.blockers()) {
                    PaperLiveFeedback.error("Blocker: " + blocker);
                }
            } else {
                String suffix = result.dependents().isEmpty() ? "" : " (including: " + String.join(", ", result.dependents()) + ")";
                PaperLiveFeedback.success("Unloaded Bukkit plugin '" + pluginName + "'" + suffix + ".");
            }
        });
    }

    private static void compileAndRefresh() {
        PluginInitializerManager initializerManager = PluginInitializerManager.instance();

        if (initializerManager == null) {
            PaperLiveFeedback.error("Cannot refresh because project discovery is not initialized.");
            complete();
            return;
        }

        Path pluginDirectory = initializerManager.pluginDirectoryPath();
        PaperLiveProjectCompiler.CompilationResult compilation = PaperLiveProjectCompiler.compileProjectsDetailed(pluginDirectory, LOGGER);

        if (!compilation.successful()) {
            String failedProjects = String.join(", ", compilation.failedProjects());
            PaperLiveFeedback.error("Compilation failed for: " + failedProjects + ". The running plugins were left unchanged.");
            complete();
            return;
        }

        MinecraftServer.getServer().execute(() -> replacePlugins(compilation));
    }

    private static void compileAndLoad(@NotNull String projectName, boolean includeDependencies) {
        PluginInitializerManager initializerManager = PluginInitializerManager.instance();
        if (initializerManager == null) {
            PaperLiveFeedback.error("Cannot load because project discovery is not initialized.");
            complete();
            return;
        }

        PaperLiveProjectCompiler.CompilationResult compilation = PaperLiveProjectCompiler.compileProjectDetailed(
            initializerManager.pluginDirectoryPath(), projectName, LOGGER
        );
        if (!compilation.successful()) {
            PaperLiveFeedback.error("Could not build source project '" + projectName + "'. Check its build log and project name.");
            complete();
            return;
        }

        MinecraftServer.getServer().execute(() -> loadPlugin(compilation, includeDependencies));
    }

    private static void replacePlugins(@NotNull PaperLiveProjectCompiler.CompilationResult compilation) {
        try {
            if (!validateRefreshDependencies(compilation)) {
                return;
            }
            PaperLiveFeedback.info("Compilation succeeded. Quiescing PaperLive plugin resources...");
            PaperPluginManagerImpl pluginManager = PaperPluginManagerImpl.getInstance();
            PaperPluginManagerImpl.PaperLiveRefreshResult preparation = pluginManager.preparePaperLiveRefresh(compilation.pluginNames());

            if (!preparation.successful()) {
                PaperLiveFeedback.error("Refresh blocked; existing plugins were restored.");

                for (String blocker : preparation.blockers()) {
                    PaperLiveFeedback.error("Blocker: " + blocker);
                }

                return;
            }

            if (!PaperLiveProjectCompiler.prepareRuntimeJars(compilation, LOGGER)) {
                PaperLiveFeedback.error("Could not prepare runtime JARs after unloading plugins.");
                return;
            }

            pluginManager.loadPaperLivePlugins(compilation.runtimeJars());
            PaperLiveFeedback.success("Source projects compiled and refreshed successfully.");
        } catch (Throwable throwable) {
            LOGGER.error("[PaperLive] Refresh failed", throwable);
            PaperLiveFeedback.error("Refresh failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            complete();
        }
    }

    private static void loadPlugin(@NotNull PaperLiveProjectCompiler.CompilationResult compilation, boolean includeDependencies) {
        try {
            String pluginName = compilation.pluginNames().getFirst();
            PaperPluginManagerImpl pluginManager = PaperPluginManagerImpl.getInstance();
            if (pluginManager.getPlugin(pluginName) != null) {
                PaperLiveFeedback.error("Bukkit plugin '" + pluginName + "' is already loaded. Run /unload " + pluginName + " first.");
                return;
            }
            Path sourceJar = compilation.sourceJars().getFirst();
            if (!confirmDependencies(sourceJar, includeDependencies)) {
                return;
            }
            if (!PaperLiveProjectCompiler.prepareRuntimeJars(compilation, LOGGER)) {
                PaperLiveFeedback.error("Could not prepare the runtime JAR for '" + pluginName + "'.");
                return;
            }

            pluginManager.loadPaperLivePlugins(compilation.runtimeJars());
            PaperLiveFeedback.success("Loaded Bukkit plugin '" + pluginName + "' from its source project.");
        } catch (Throwable throwable) {
            LOGGER.error("[PaperLive] Loading a source project failed", throwable);
            PaperLiveFeedback.error("Load failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            complete();
        }
    }

    private static void loadBukkitPlugin(@NotNull Path pluginJar, boolean includeDependencies) {
        try {
            if (!confirmDependencies(pluginJar, includeDependencies)) {
                return;
            }
            Plugin plugin = PaperPluginManagerImpl.getInstance().loadBukkitPlugin(pluginJar);
            PaperLiveFeedback.success("Loaded Bukkit plugin '" + plugin.getPluginMeta().getName() + "' from " + pluginJar.getFileName() + ".");
        } catch (Throwable throwable) {
            LOGGER.error("[PaperLive] Loading Bukkit plugin JAR failed", throwable);
            PaperLiveFeedback.error("Load failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            complete();
        }
    }

    private static @Nullable Path findPluginJar(@NotNull Path pluginDirectory, @NotNull String target) {
        if (!Files.isDirectory(pluginDirectory)) {
            return null;
        }
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        String expectedFileName = normalizedTarget.endsWith(".jar") ? normalizedTarget : normalizedTarget + ".jar";
        try (java.util.stream.Stream<Path> files = Files.list(pluginDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).equals(expectedFileName))
                .findFirst()
                .orElse(null);
        } catch (IOException exception) {
            LOGGER.warn("[PaperLive] Cannot inspect plugin directory {}", pluginDirectory, exception);
            return null;
        }
    }

    /** Loads the hard Bukkit dependencies of a JAR in dependency-first order. */
    private static boolean confirmDependencies(@NotNull Path pluginJar, boolean includeDependencies) {
        PluginInitializerManager initializerManager = PluginInitializerManager.instance();
        if (initializerManager == null) {
            PaperLiveFeedback.error("Cannot resolve plugin dependencies because project discovery is not initialized.");
            return false;
        }

        DependencyLoadPlan plan = createDependencyLoadPlan(initializerManager.pluginDirectoryPath(), pluginJar);
        if (!plan.missingDependencies().isEmpty()) {
            PaperLiveFeedback.error("Missing Bukkit plugin JARs for: " + String.join(", ", plan.missingDependencies()) + ".");
            return false;
        }
        if (!includeDependencies && !plan.jars().isEmpty()) {
            PluginDescriptionFile description = readPluginDescription(pluginJar);
            String pluginName = description == null ? pluginJar.getFileName().toString() : description.getName();
            PaperLiveFeedback.error("'" + pluginName + "' needs: " + String.join(", ", plan.jars().stream().map(path -> path.getFileName().toString()).toList()) + ". It was not loaded.");
            PaperLiveFeedback.info("To load it with its dependencies, run: /load " + pluginName + " dependents");
            return false;
        }

        if (!includeDependencies) {
            return true;
        }

        try {
            PaperPluginManagerImpl pluginManager = PaperPluginManagerImpl.getInstance();
            for (Path dependencyJar : plan.jars()) {
                Plugin dependency = pluginManager.loadBukkitPlugin(dependencyJar);
                PaperLiveFeedback.info("Loaded dependency '" + dependency.getPluginMeta().getName() + "'.");
            }
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("[PaperLive] Loading a Bukkit dependency failed", throwable);
            PaperLiveFeedback.error("Dependency load failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return false;
        }
    }

    /** Refuses a refresh before it unloads anything when a source plugin needs an inactive dependency. */
    private static boolean validateRefreshDependencies(@NotNull PaperLiveProjectCompiler.CompilationResult compilation) {
        PluginInitializerManager initializerManager = PluginInitializerManager.instance();
        if (initializerManager == null) {
            PaperLiveFeedback.error("Cannot validate dependencies because project discovery is not initialized.");
            return false;
        }

        boolean valid = true;
        for (Path sourceJar : compilation.sourceJars()) {
            PluginDescriptionFile description = readPluginDescription(sourceJar);
            if (description == null) {
                continue;
            }
            DependencyLoadPlan plan = createDependencyLoadPlan(initializerManager.pluginDirectoryPath(), sourceJar);
            if (!plan.missingDependencies().isEmpty()) {
                PaperLiveFeedback.error("You are missing required Bukkit plugin(s) for '" + description.getName() + "': " + String.join(", ", plan.missingDependencies()) + ".");
                valid = false;
            }
            if (!plan.jars().isEmpty()) {
                String dependencies = String.join(", ", plan.jars().stream().map(path -> path.getFileName().toString()).toList());
                PaperLiveFeedback.error("Required Bukkit plugin(s) for '" + description.getName() + "' are not loaded: " + dependencies + ".");
                PaperLiveFeedback.info("Load them first with: /load " + description.getName() + " dependents");
                valid = false;
            }
        }
        return valid;
    }

    private static @NotNull DependencyLoadPlan createDependencyLoadPlan(@NotNull Path pluginDirectory, @NotNull Path pluginJar) {
        Map<String, Path> jarsByPluginName = new HashMap<>();
        if (Files.isDirectory(pluginDirectory)) {
            try (java.util.stream.Stream<Path> files = Files.list(pluginDirectory)) {
                files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(path -> {
                        PluginDescriptionFile description = readPluginDescription(path);
                        if (description != null) {
                            jarsByPluginName.putIfAbsent(description.getName().toLowerCase(Locale.ROOT), path);
                            for (String provided : description.getProvides()) {
                                jarsByPluginName.putIfAbsent(provided.toLowerCase(Locale.ROOT), path);
                            }
                        }
                    });
            } catch (IOException exception) {
                LOGGER.warn("[PaperLive] Cannot inspect plugin dependencies in {}", pluginDirectory, exception);
            }
        }

        List<Path> loadOrder = new ArrayList<>();
        List<String> missingDependencies = new ArrayList<>();
        collectDependencies(pluginJar, jarsByPluginName, new HashSet<>(), new HashSet<>(), loadOrder, missingDependencies);
        return new DependencyLoadPlan(List.copyOf(loadOrder), List.copyOf(missingDependencies));
    }

    private static void collectDependencies(@NotNull Path pluginJar, @NotNull Map<String, Path> jarsByPluginName, @NotNull Set<Path> visitedJars, @NotNull Set<Path> scheduledJars, @NotNull List<Path> loadOrder, @NotNull List<String> missingDependencies) {
        if (!visitedJars.add(pluginJar)) {
            return;
        }
        PluginDescriptionFile description = readPluginDescription(pluginJar);
        if (description == null) {
            return;
        }

        for (String dependencyName : description.getDepend()) {
            if (PaperPluginManagerImpl.getInstance().getPlugin(dependencyName) != null) {
                continue;
            }
            Path dependencyJar = jarsByPluginName.get(dependencyName.toLowerCase(Locale.ROOT));
            if (dependencyJar == null) {
                if (!missingDependencies.contains(dependencyName)) {
                    missingDependencies.add(dependencyName);
                }
                continue;
            }
            collectDependencies(dependencyJar, jarsByPluginName, visitedJars, scheduledJars, loadOrder, missingDependencies);
            if (scheduledJars.add(dependencyJar)) {
                loadOrder.add(dependencyJar);
            }
        }
    }

    private static @Nullable PluginDescriptionFile readPluginDescription(@NotNull Path pluginJar) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(pluginJar.toFile())) {
            java.util.jar.JarEntry descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null) {
                return null;
            }
            try (java.io.InputStream input = jar.getInputStream(descriptor)) {
                return new PluginDescriptionFile(input);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private record DependencyLoadPlan(@NotNull List<Path> jars, @NotNull List<String> missingDependencies) {
    }

    private static void complete() {
        REFRESHING.set(false);

        if (REFRESH_PENDING.compareAndSet(true, false)) {
            requestRefresh("queued source changes");
        }
    }
}
