package io.papermc.paper.plugin;

import org.jetbrains.annotations.NotNull;

/** Public bridge used by the desktop PaperLive tool to invoke plugin actions. */
public final class PaperLivePluginActions {

    private PaperLivePluginActions() {
    }

    public static void refresh() {
        PaperLiveRefreshService.requestRefresh("manual request from the Dev Tool");
    }

    public static void load(@NotNull String name) {
        PaperLiveRefreshService.requestLoad(name, false);
    }

    public static void unload(@NotNull String name) {
        PaperLiveRefreshService.requestUnload(name, false);
    }

    public static void enable(@NotNull String name) {
        PaperLiveRefreshService.requestEnable(name, false);
    }

    public static void disable(@NotNull String name) {
        PaperLiveRefreshService.requestDisable(name, false);
    }
}
