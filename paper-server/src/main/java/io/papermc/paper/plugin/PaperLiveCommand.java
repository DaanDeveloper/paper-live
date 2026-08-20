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
    private static final List<String> SUBCOMMANDS = List.of("refresh", "projects", "help");

    public PaperLiveCommand(@NotNull String name) {
        super(name);
        this.description = "PaperLive development commands";
        this.usageMessage = "/paperlive <refresh|projects|help>";
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
            sender.sendMessage("§ePaperLive: §f/paperlive refresh §7| §f/paperlive projects");
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
