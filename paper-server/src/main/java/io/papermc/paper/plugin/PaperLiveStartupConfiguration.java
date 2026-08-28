package io.papermc.paper.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/** Stores the plugins PaperLive must leave inactive during server startup. */
final class PaperLiveStartupConfiguration {

    private static final String DISABLED_PLUGINS = "disabled-plugins";

    private PaperLiveStartupConfiguration() {
    }

    static boolean isEnabled(@NotNull Path pluginDirectory, @NotNull String... identifiers) {
        Set<String> disabledPlugins = disabledPlugins(pluginDirectory);
        for (String identifier : identifiers) {
            if (disabledPlugins.contains(normalize(identifier))) {
                return false;
            }
        }
        return true;
    }

    static boolean setEnabled(@NotNull Path pluginDirectory, @NotNull String pluginName, boolean enabled, @NotNull Logger logger) {
        Path configurationFile = configurationFile(pluginDirectory);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configurationFile.toFile());
        Set<String> disabledPlugins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        disabledPlugins.addAll(configuration.getStringList(DISABLED_PLUGINS));

        boolean changed = enabled
            ? disabledPlugins.removeIf(disabledPlugin -> disabledPlugin.equalsIgnoreCase(pluginName))
            : disabledPlugins.add(pluginName);
        if (!changed) {
            return true;
        }

        configuration.set(DISABLED_PLUGINS, disabledPlugins.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        try {
            Files.createDirectories(configurationFile.getParent());
            configuration.save(configurationFile.toFile());
            return true;
        } catch (IOException exception) {
            logger.error("[PaperLive] Cannot save startup plugin configuration {}", configurationFile, exception);
            return false;
        }
    }

    private static @NotNull Set<String> disabledPlugins(@NotNull Path pluginDirectory) {
        Set<String> disabledPlugins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        disabledPlugins.addAll(YamlConfiguration.loadConfiguration(configurationFile(pluginDirectory).toFile()).getStringList(DISABLED_PLUGINS));
        return disabledPlugins.stream().map(PaperLiveStartupConfiguration::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static @NotNull Path configurationFile(@NotNull Path pluginDirectory) {
        return pluginDirectory.resolve("PaperLive").resolve("config.yml");
    }

    private static @NotNull String normalize(@NotNull String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }
}
