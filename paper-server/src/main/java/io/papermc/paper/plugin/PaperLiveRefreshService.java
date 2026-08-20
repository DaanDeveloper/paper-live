package io.papermc.paper.plugin;

import com.mojang.logging.LogUtils;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
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

    private static void replacePlugins(@NotNull PaperLiveProjectCompiler.CompilationResult compilation) {
        try {
            PaperLiveFeedback.info("Compilation succeeded. Quiescing plugin resources...");
            PaperPluginManagerImpl.PaperLiveRefreshResult preparation = PaperPluginManagerImpl.getInstance().preparePaperLiveRefresh();

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

            PaperLiveProjectCompiler.skipNextCompilation();
            Bukkit.getServer().reload();
            PaperLiveFeedback.success("Source projects compiled and refreshed successfully.");
        } catch (Throwable throwable) {
            LOGGER.error("[PaperLive] Refresh failed", throwable);
            PaperLiveFeedback.error("Refresh failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            complete();
        }
    }

    private static void complete() {
        REFRESHING.set(false);

        if (REFRESH_PENDING.compareAndSet(true, false)) {
            requestRefresh("queued source changes");
        }
    }
}
