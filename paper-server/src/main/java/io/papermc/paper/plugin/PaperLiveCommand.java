package io.papermc.paper.plugin;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Administrative commands for conventional source plugins managed by PaperLive.
 */
public final class PaperLiveCommand extends Command {

    private static final String PERMISSION = PaperLiveFeedback.PERMISSION;
    private static final List<String> SUBCOMMANDS = List.of("refresh", "load", "unload", "projects", "help");

    public PaperLiveCommand(@NotNull String name) {
        super(name);
        this.description = "PaperLive development commands";
        this.usageMessage = "/paperlive <refresh|load <plugin> [dependents]|unload <plugin> [dependents]|projects|help>";
        this.setAliases(List.of("plive"));
        this.setPermission(PERMISSION);
        Bukkit.getServer().getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] arguments) {
        if (!this.testPermission(sender)) {
            return true;
        }

        if (arguments.length == 0 || arguments[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§ePaperLive: §f/plive refresh §7| §f/plive load <plugin> [dependents] §7| §f/plive unload <plugin> [dependents] §7| §f/plive projects");
            return true;
        }

        if (arguments[0].equalsIgnoreCase("projects")) {
            this.sendProjects(sender);
            return true;
        }

        if (arguments[0].equalsIgnoreCase("refresh")) {
            PaperLiveRefreshService.requestRefresh("manual admin request");
            return true;
        }

        if (arguments[0].equalsIgnoreCase("load") && (arguments.length == 2 || (arguments.length == 3 && arguments[2].equalsIgnoreCase("dependents")))) {
            PaperLiveRefreshService.requestLoad(arguments[1], arguments.length == 3);
            return true;
        }

        if (arguments[0].equalsIgnoreCase("unload") && (arguments.length == 2 || (arguments.length == 3 && arguments[2].equalsIgnoreCase("dependents")))) {
            PaperLiveRefreshService.requestUnload(arguments[1], arguments.length == 3);
            return true;
        }

        sender.sendMessage("§cUsage: " + this.usageMessage);
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] arguments, @Nullable org.bukkit.Location location) throws IllegalArgumentException {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (arguments.length == 1) {
            return this.completions(arguments[0]);
        }

        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("load")) {
            PluginInitializerManager initializerManager = PluginInitializerManager.instance();
            if (initializerManager == null) {
                return List.of();
            }
            java.nio.file.Path pluginDirectory = initializerManager.pluginDirectoryPath();
            List<String> sourceProjects = PaperLiveProjectCompiler.findProjectDirectories(pluginDirectory.resolve("PaperLive").resolve("projects")).stream()
                .map(project -> project.getFileName().toString())
                .toList();
            List<String> pluginJars;
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(pluginDirectory)) {
                pluginJars = files
                    .filter(java.nio.file.Files::isRegularFile)
                    .map(java.nio.file.Path::getFileName)
                    .map(java.nio.file.Path::toString)
                    .filter(file -> file.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .map(file -> file.substring(0, file.length() - ".jar".length()))
                    .toList();
            } catch (java.io.IOException ignored) {
                pluginJars = List.of();
            }
            String input = arguments[1].toLowerCase(java.util.Locale.ROOT);
            return java.util.stream.Stream.concat(sourceProjects.stream(), pluginJars.stream())
                .distinct()
                .filter(candidate -> candidate.toLowerCase(java.util.Locale.ROOT).startsWith(input))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        }

        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("unload")) {
            return java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(plugin -> plugin.getPluginMeta().getName())
                .filter(plugin -> plugin.toLowerCase(java.util.Locale.ROOT).startsWith(arguments[1].toLowerCase(java.util.Locale.ROOT)))
                .toList();
        }

        if (arguments.length == 3 && (arguments[0].equalsIgnoreCase("load") || arguments[0].equalsIgnoreCase("unload"))) {
            return "dependents".startsWith(arguments[2].toLowerCase(java.util.Locale.ROOT)) ? List.of("dependents") : List.of();
        }

        return List.of();
    }

    /**
     * Returns tab-completion candidates for the first command argument.
     *
     * @param input the partially typed subcommand
     * @return matching subcommands in deterministic order
     */
    static @NotNull List<String> completions(@NotNull String input) {
        return SUBCOMMANDS.stream()
            .filter(subcommand -> subcommand.startsWith(input.toLowerCase(java.util.Locale.ROOT)))
            .toList();
    }

    private void sendProjects(@NotNull CommandSender sender) {
        PluginInitializerManager pluginInitializerManager = PluginInitializerManager.instance();

        if (pluginInitializerManager == null) {
            sender.sendMessage("§c[PaperLive] Project discovery is not initialized yet.");
            return;
        }

        List<java.nio.file.Path> projects = PaperLiveProjectCompiler.findProjectDirectories(
            pluginInitializerManager.pluginDirectoryPath().resolve("PaperLive").resolve("projects")
        );

        if (projects.isEmpty()) {
            sender.sendMessage("§e[PaperLive] No source projects found.");
            return;
        }

        String projectNames = projects.stream()
            .map(project -> project.getFileName().toString())
            .collect(java.util.stream.Collectors.joining(", "));
        sender.sendMessage("§a[PaperLive] Source projects: §f" + projectNames);
    }
}
