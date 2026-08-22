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
    private final @Nullable String fixedSubcommand;

    public PaperLiveCommand(@NotNull String name) {
        this(name, null);
    }

    /**
     * Creates either the PaperLive command or a direct command for one of its subcommands.
     *
     * @param name the registered command name
     * @param fixedSubcommand the subcommand to supply automatically, or {@code null} for the main command
     */
    public PaperLiveCommand(@NotNull String name, @Nullable String fixedSubcommand) {
        super(name);
        this.fixedSubcommand = fixedSubcommand;
        this.description = "PaperLive development commands";
        this.usageMessage = fixedSubcommand == null
            ? "/paperlive <refresh|load <plugin> [dependents]|unload <plugin> [dependents]|projects|help>"
            : usageMessage(name, fixedSubcommand);
        if (fixedSubcommand == null) {
            this.setAliases(List.of("plive"));
        }
        this.setPermission(PERMISSION);
        if (Bukkit.getServer().getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getServer().getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] arguments) {
        if (!this.testPermission(sender)) {
            return true;
        }

        final String[] commandArguments = this.commandArguments(arguments);
        if (commandArguments.length == 0 || commandArguments[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§ePaperLive: §f/refresh §7| §f/load <plugin> [dependents] §7| §f/unload <plugin> [dependents] §7| §f/projects §7(also /paperlive and /plive)");
            return true;
        }

        if (commandArguments[0].equalsIgnoreCase("projects")) {
            this.sendProjects(sender);
            return true;
        }

        if (commandArguments[0].equalsIgnoreCase("refresh")) {
            PaperLiveRefreshService.requestRefresh("manual admin request");
            return true;
        }

        if (commandArguments[0].equalsIgnoreCase("load") && (commandArguments.length == 2 || (commandArguments.length == 3 && commandArguments[2].equalsIgnoreCase("dependents")))) {
            PaperLiveRefreshService.requestLoad(commandArguments[1], commandArguments.length == 3);
            return true;
        }

        if (commandArguments[0].equalsIgnoreCase("unload") && (commandArguments.length == 2 || (commandArguments.length == 3 && commandArguments[2].equalsIgnoreCase("dependents")))) {
            PaperLiveRefreshService.requestUnload(commandArguments[1], commandArguments.length == 3);
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

        final String[] commandArguments = this.commandArguments(arguments);
        if (commandArguments.length == 1 && this.fixedSubcommand == null) {
            return completions(commandArguments[0]);
        }

        if (commandArguments.length == 2 && commandArguments[0].equalsIgnoreCase("load")) {
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
            String input = commandArguments[1].toLowerCase(java.util.Locale.ROOT);
            return java.util.stream.Stream.concat(sourceProjects.stream(), pluginJars.stream())
                .distinct()
                .filter(candidate -> candidate.toLowerCase(java.util.Locale.ROOT).startsWith(input))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        }

        if (commandArguments.length == 2 && commandArguments[0].equalsIgnoreCase("unload")) {
            return java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(plugin -> plugin.getPluginMeta().getName())
                .filter(plugin -> plugin.toLowerCase(java.util.Locale.ROOT).startsWith(commandArguments[1].toLowerCase(java.util.Locale.ROOT)))
                .toList();
        }

        if (commandArguments.length == 3 && (commandArguments[0].equalsIgnoreCase("load") || commandArguments[0].equalsIgnoreCase("unload"))) {
            return "dependents".startsWith(commandArguments[2].toLowerCase(java.util.Locale.ROOT)) ? List.of("dependents") : List.of();
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

    public static @NotNull List<String> subcommands() {
        return SUBCOMMANDS;
    }

    private @NotNull String[] commandArguments(@NotNull String[] arguments) {
        return commandArguments(this.fixedSubcommand, arguments);
    }

    static @NotNull String[] commandArguments(@Nullable String fixedSubcommand, @NotNull String[] arguments) {
        if (fixedSubcommand == null) {
            return arguments;
        }

        String[] commandArguments = new String[arguments.length + 1];
        commandArguments[0] = fixedSubcommand;
        System.arraycopy(arguments, 0, commandArguments, 1, arguments.length);
        return commandArguments;
    }

    private static @NotNull String usageMessage(@NotNull String name, @NotNull String subcommand) {
        return switch (subcommand) {
            case "load", "unload" -> "/" + name + " <plugin> [dependents]";
            default -> "/" + name;
        };
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
