package io.papermc.paper.plugin;

import io.papermc.paper.plugin.debug.PaperLiveScenarioRecorder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Commands for recording and replaying PaperLive scenarios without the debugger window. */
public final class PaperLiveScenarioCommand extends Command {

    private static final String PERMISSION = PaperLiveFeedback.PERMISSION;
    private static final List<String> SUBCOMMANDS = List.of("create", "record", "stop", "cancel", "replay", "delete", "list", "status", "help");
    private final java.util.Map<java.util.UUID, String> recordingNames = new java.util.HashMap<>();

    public PaperLiveScenarioCommand() {
        super("scenario");
        this.description = "PaperLive scenario commands";
        this.usageMessage = "/scenario <create|record> <name>|stop [name]|cancel|replay <name>|delete <name>|list|status";
        this.setAliases(List.of("senario"));
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
        if (arguments.length == 0 || arguments[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§ePaperLive scenarios: §f/scenario create <name> §7| §f/scenario stop [name] §7| §f/scenario replay <name> §7| §f/scenario delete <name> §7| §f/scenario list §7| §f/scenario status");
            return true;
        }

        String action = arguments[0].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("list")) {
            List<String> scenarios = PaperLiveScenarioRecorder.list(this.scenarioDirectory());
            sender.sendMessage(scenarios.isEmpty() ? "§e[PaperLive] No saved scenarios." : "§a[PaperLive] Scenarios: §f" + String.join(", ", scenarios));
            return true;
        }
        if (action.equals("delete")) {
            try {
                return this.delete(sender, arguments);
            } catch (IOException | RuntimeException exception) {
                sender.sendMessage("§c[PaperLive] " + exception.getMessage());
                return true;
            }
        }
        if (action.equals("status")) {
            return this.status(sender, arguments);
        }

        Player player = this.requirePlayer(sender);
        if (player == null) {
            return true;
        }

        try {
            return switch (action) {
                case "create", "record" -> this.start(sender, player, arguments);
                case "stop" -> this.stop(sender, player, arguments);
                case "cancel" -> this.cancel(sender, player, arguments);
                case "replay" -> this.replay(sender, player, arguments);
                default -> this.usage(sender);
            };
        } catch (IOException | RuntimeException exception) {
            sender.sendMessage("§c[PaperLive] " + exception.getMessage());
            return true;
        }
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] arguments, @Nullable org.bukkit.Location location) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (arguments.length == 1) {
            return completions(arguments[0]);
        }
        if (arguments.length == 2 && needsScenarioName(arguments[0])) {
            return PaperLiveScenarioRecorder.list(this.scenarioDirectory()).stream()
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(arguments[1].toLowerCase(java.util.Locale.ROOT)))
                .toList();
        }
        return List.of();
    }

    static @NotNull List<String> completions(@NotNull String input) {
        String normalized = input.toLowerCase(java.util.Locale.ROOT);
        return SUBCOMMANDS.stream().filter(action -> action.startsWith(normalized)).toList();
    }

    private boolean start(CommandSender sender, Player player, String[] arguments) {
        if (arguments.length != 2) {
            return this.usage(sender);
        }
        String name = arguments[1];
        validateName(name);
        PaperLiveScenarioRecorder.instance().start(player);
        this.recordingNames.put(player.getUniqueId(), name);
        sender.sendMessage("§a[PaperLive] Recording scenario '" + name + "'. Use /scenario stop when finished.");
        return true;
    }

    private boolean stop(CommandSender sender, Player player, String[] arguments) throws IOException {
        if (arguments.length > 2) {
            return this.usage(sender);
        }
        String name = arguments.length == 2 ? arguments[1] : this.recordingNames.get(player.getUniqueId());
        if (name == null) {
            sender.sendMessage("§c[PaperLive] Supply a scenario name: /scenario stop <name>.");
            return true;
        }
        PaperLiveScenarioRecorder.Scenario scenario = PaperLiveScenarioRecorder.instance().stop(name, this.scenarioDirectory(), player);
        this.recordingNames.remove(player.getUniqueId());
        sender.sendMessage("§a[PaperLive] Saved scenario '" + scenario.name() + "' with " + scenario.steps().size() + " steps.");
        return true;
    }

    private boolean cancel(CommandSender sender, Player player, String[] arguments) {
        if (arguments.length != 1) {
            return this.usage(sender);
        }
        PaperLiveScenarioRecorder.RecordingStatus status = PaperLiveScenarioRecorder.instance().status();
        if (status == null || !status.playerId().equals(player.getUniqueId())) {
            sender.sendMessage("§c[PaperLive] You do not have an active scenario recording.");
            return true;
        }
        PaperLiveScenarioRecorder.instance().cancel();
        this.recordingNames.remove(player.getUniqueId());
        sender.sendMessage("§e[PaperLive] Scenario recording cancelled.");
        return true;
    }

    private boolean replay(CommandSender sender, Player player, String[] arguments) throws IOException {
        if (arguments.length != 2) {
            return this.usage(sender);
        }
        PaperLiveScenarioRecorder.ReplayResult result = PaperLiveScenarioRecorder.instance().replay(this.scenarioFile(arguments[1]), player);
        sender.sendMessage(result.successful()
            ? "§a[PaperLive] Scenario '" + result.scenarioName() + "' passed (" + result.commandsExecuted() + " commands, " + result.actionsReplayed() + " actions)."
            : "§c[PaperLive] Scenario '" + result.scenarioName() + "' failed: " + String.join("; ", result.failures()));
        return true;
    }

    private boolean delete(CommandSender sender, String[] arguments) throws IOException {
        if (arguments.length != 2) {
            return this.usage(sender);
        }
        boolean deleted = PaperLiveScenarioRecorder.delete(this.scenarioDirectory(), arguments[1]);
        sender.sendMessage(deleted ? "§a[PaperLive] Deleted scenario '" + arguments[1] + "'." : "§e[PaperLive] Scenario '" + arguments[1] + "' does not exist.");
        return true;
    }

    private boolean status(CommandSender sender, String[] arguments) {
        if (arguments.length != 1) {
            return this.usage(sender);
        }
        PaperLiveScenarioRecorder.RecordingStatus status = PaperLiveScenarioRecorder.instance().status();
        if (status == null) {
            sender.sendMessage("§e[PaperLive] No scenario is being recorded.");
        } else {
            String name = this.recordingNames.getOrDefault(status.playerId(), "unnamed");
            sender.sendMessage("§e[PaperLive] Recording '" + name + "' for " + status.playerName() + " (" + status.stepCount() + " steps).");
        }
        return true;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage("§cUsage: " + this.usageMessage);
        return true;
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("§c[PaperLive] This scenario action must be run by the player being recorded or replayed.");
        return null;
    }

    private @NotNull Path scenarioDirectory() {
        PluginInitializerManager initializer = PluginInitializerManager.instance();
        return initializer == null
            ? Bukkit.getWorldContainer().toPath().resolve("plugins/PaperLive/scenarios")
            : initializer.pluginDirectoryPath().resolve("PaperLive").resolve("scenarios");
    }

    private @NotNull Path scenarioFile(String name) {
        validateName(name);
        return this.scenarioDirectory().resolve(name + ".yml");
    }

    private static boolean needsScenarioName(String action) {
        return action.equalsIgnoreCase("replay") || action.equalsIgnoreCase("delete");
    }

    private static void validateName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Scenario name must use 1-64 letters, numbers, '.', '_' or '-'.");
        }
    }
}
