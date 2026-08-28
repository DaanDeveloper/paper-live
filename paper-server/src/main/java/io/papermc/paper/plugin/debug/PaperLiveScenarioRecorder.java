package io.papermc.paper.plugin.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Records one player's commands and actions, then replays them with final-state assertions. */
public final class PaperLiveScenarioRecorder {

    private static final PaperLiveScenarioRecorder INSTANCE = new PaperLiveScenarioRecorder();
    private @Nullable Recording recording;

    private PaperLiveScenarioRecorder() {
    }

    public static PaperLiveScenarioRecorder instance() {
        return INSTANCE;
    }

    public synchronized void start(@NotNull Player player) {
        if (this.recording != null) {
            throw new IllegalStateException("A scenario is already being recorded for " + this.recording.playerName());
        }
        this.recording = new Recording(player.getUniqueId(), player.getName(), PlayerState.capture(player), new ArrayList<>());
    }

    public synchronized Scenario stop(@NotNull String scenarioName, @NotNull Path scenarioDirectory, @NotNull Player player) throws IOException {
        validateName(scenarioName);
        Recording active = this.recording;
        if (active == null || !active.playerId().equals(player.getUniqueId())) {
            throw new IllegalStateException("No scenario recording is active for " + player.getName());
        }
        Scenario scenario = new Scenario(scenarioName, active.playerName(), List.copyOf(active.steps()), active.initial(), PlayerState.capture(player));
        save(scenarioDirectory.resolve(scenarioName + ".yml"), scenario);
        this.recording = null;
        return scenario;
    }

    public synchronized void cancel() {
        this.recording = null;
    }

    public synchronized @Nullable RecordingStatus status() {
        return this.recording == null ? null : new RecordingStatus(this.recording.playerId(), this.recording.playerName(), this.recording.steps().size());
    }

    public synchronized void recordCommand(PaperLiveCommandTrace trace) {
        if (this.recording == null || trace.playerUuid() == null || !this.recording.playerId().toString().equals(trace.playerUuid())) {
            return;
        }
        if (trace.commandLabel().equalsIgnoreCase("scenario") || trace.commandLabel().equalsIgnoreCase("senario")) {
            return;
        }
        this.recording.steps().add(new Step("COMMAND", trace.commandLine(), Map.of(
            "owner", trace.ownerName(),
            "completed", Boolean.toString(trace.completedNormally()),
            "result", Boolean.toString(trace.executorResult())
        )));
    }

    public synchronized void observe(Event event) {
        if (this.recording == null || event instanceof PlayerCommandPreprocessEvent) {
            return;
        }
        Player actor;
        if (event instanceof PlayerEvent playerEvent) {
            actor = playerEvent.getPlayer();
        } else if (event instanceof InventoryClickEvent inventoryClick && inventoryClick.getWhoClicked() instanceof Player player) {
            actor = player;
        } else {
            return;
        }
        if (!actor.getUniqueId().equals(this.recording.playerId())) {
            return;
        }
        PaperLiveDebugException.EventSnapshot snapshot = PaperLiveEventContext.snapshot(event);
        Map<String, String> details = new LinkedHashMap<>(snapshot.context());
        details.putIfAbsent("player", actor.getName());
        if (snapshot.cancelled() != null) {
            details.put("cancelled", snapshot.cancelled().toString());
        }
        String type = "OBSERVATION";
        if (event instanceof PlayerInteractEvent interactEvent && interactEvent.getAction() == Action.RIGHT_CLICK_BLOCK && interactEvent.getClickedBlock() != null) {
            type = "PLAYER_INTERACT";
        } else if (event instanceof InventoryClickEvent clickEvent) {
            type = "INVENTORY_CLICK";
            InventoryView view = clickEvent.getView();
            details.put("rawSlot", Integer.toString(clickEvent.getRawSlot()));
            details.put("slot", Integer.toString(clickEvent.getSlot()));
            details.put("click", clickEvent.getClick().name());
            details.put("action", clickEvent.getAction().name());
            details.put("hotbarButton", Integer.toString(clickEvent.getHotbarButton()));
            details.put("inventoryType", view.getType().name());
            details.put("inventoryTitle", view.getTitle());
            details.put("topSize", Integer.toString(view.getTopInventory().getSize()));
            ItemStack current = clickEvent.getCurrentItem();
            if (current != null && !current.getType().isAir()) {
                details.put("item", current.getType().name());
                if (current.hasItemMeta() && current.getItemMeta().hasDisplayName()) {
                    details.put("itemName", current.getItemMeta().getDisplayName());
                }
            }
        }
        this.recording.steps().add(new Step(type, snapshot.name(), details));
    }

    public ReplayResult replay(@NotNull Path scenarioFile, @NotNull Player player) throws IOException {
        return this.beginReplay(scenarioFile, player).advance(Set.of(), false).result();
    }

    public ReplaySession beginReplay(@NotNull Path scenarioFile, @NotNull Player player) throws IOException {
        return new ReplaySession(load(scenarioFile), player);
    }

    private static void replayPlayerInteract(Step step, Player player) {
        Map<String, String> details = step.details();
        String world = required(details, "clickedBlockWorld");
        if (!player.getWorld().getName().equals(world)) {
            throw new IllegalStateException("Expected world " + world + " but player is in " + player.getWorld().getName());
        }
        BlockPos position = new BlockPos(integer(details, "clickedBlockX"), integer(details, "clickedBlockY"), integer(details, "clickedBlockZ"));
        Direction direction = enumValue(Direction.class, required(details, "blockFace"), "block face");
        InteractionHand hand = switch (details.getOrDefault("hand", "HAND")) {
            case "HAND" -> InteractionHand.MAIN_HAND;
            case "OFF_HAND" -> InteractionHand.OFF_HAND;
            default -> throw new IllegalStateException("Unsupported hand " + details.get("hand"));
        };
        Vec3 hitLocation = new Vec3(
            decimal(details, "interactionX", position.getX() + 0.5D),
            decimal(details, "interactionY", position.getY() + 0.5D),
            decimal(details, "interactionZ", position.getZ() + 0.5D)
        );
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        serverPlayer.gameMode.useItemOn(
            serverPlayer,
            serverPlayer.level(),
            serverPlayer.getItemInHand(hand),
            hand,
            new BlockHitResult(hitLocation, direction, position, false)
        );
    }

    private static String replayInventoryClick(Step step, Player player) {
        Map<String, String> details = step.details();
        InventoryView view = player.getOpenInventory();
        String expectedType = required(details, "inventoryType");
        if (!view.getType().name().equals(expectedType)) {
            throw new IllegalStateException("Expected open inventory " + expectedType + " but found " + view.getType().name());
        }
        String expectedTitle = details.get("inventoryTitle");
        if (expectedTitle != null && !expectedTitle.equals(view.getTitle())) {
            throw new IllegalStateException("Expected inventory title '" + expectedTitle + "' but found '" + view.getTitle() + "'");
        }

        int recordedSlot = integer(details, "rawSlot");
        int rawSlot = resolveRawSlot(view, recordedSlot, details.get("item"), details.get("itemName"));
        ClickInput input = clickInput(enumValue(ClickType.class, required(details, "click"), "click type"), integer(details, "hotbarButton", -1));
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        serverPlayer.connection.handleContainerClick(new ServerboundContainerClickPacket(
            serverPlayer.containerMenu.containerId,
            serverPlayer.containerMenu.getStateId(),
            (short) rawSlot,
            input.button(),
            input.input(),
            new Int2ObjectOpenHashMap<>(),
            HashedStack.EMPTY
        ));
        return (rawSlot == recordedSlot ? "slot " + rawSlot : "slot " + recordedSlot + " remapped to " + rawSlot)
            + " • " + details.get("click") + (details.containsKey("item") ? " • " + details.get("item") : "");
    }

    private static int resolveRawSlot(InventoryView view, int recordedSlot, @Nullable String material, @Nullable String itemName) {
        if (material == null || itemMatches(view.getItem(recordedSlot), material, itemName)) {
            return recordedSlot;
        }
        int match = -1;
        int topSize = view.getTopInventory().getSize();
        for (int slot = 0; slot < topSize; slot++) {
            if (itemMatches(view.getItem(slot), material, itemName)) {
                if (match >= 0) {
                    throw new IllegalStateException("Recorded item " + material + " is no longer unique in the open inventory");
                }
                match = slot;
            }
        }
        if (match < 0) {
            throw new IllegalStateException("Recorded item " + material + " is not present in the open inventory");
        }
        return match;
    }

    private static boolean itemMatches(@Nullable ItemStack item, String material, @Nullable String itemName) {
        if (item == null || !item.getType().name().equals(material)) {
            return false;
        }
        if (itemName == null) {
            return true;
        }
        return item.hasItemMeta() && item.getItemMeta().hasDisplayName() && itemName.equals(item.getItemMeta().getDisplayName());
    }

    static ClickInput clickInput(ClickType click, int hotbarButton) {
        return switch (click) {
            case LEFT, WINDOW_BORDER_LEFT -> new ClickInput(ContainerInput.PICKUP, (byte) 0);
            case RIGHT, WINDOW_BORDER_RIGHT -> new ClickInput(ContainerInput.PICKUP, (byte) 1);
            case SHIFT_LEFT -> new ClickInput(ContainerInput.QUICK_MOVE, (byte) 0);
            case SHIFT_RIGHT -> new ClickInput(ContainerInput.QUICK_MOVE, (byte) 1);
            case NUMBER_KEY -> new ClickInput(ContainerInput.SWAP, (byte) hotbarButton);
            case SWAP_OFFHAND -> new ClickInput(ContainerInput.SWAP, (byte) 40);
            case MIDDLE -> new ClickInput(ContainerInput.CLONE, (byte) 2);
            case DROP -> new ClickInput(ContainerInput.THROW, (byte) 0);
            case CONTROL_DROP -> new ClickInput(ContainerInput.THROW, (byte) 1);
            case DOUBLE_CLICK -> new ClickInput(ContainerInput.PICKUP_ALL, (byte) 0);
            case CREATIVE, UNKNOWN -> throw new IllegalStateException("Unsupported replay click " + click);
        };
    }

    private static String describeInteraction(Step step) {
        Map<String, String> details = step.details();
        return details.getOrDefault("action", "RIGHT_CLICK_BLOCK") + " • " + details.getOrDefault("clickedBlock", "block")
            + " @ " + details.getOrDefault("clickedBlockX", "?") + ", " + details.getOrDefault("clickedBlockY", "?") + ", " + details.getOrDefault("clickedBlockZ", "?");
    }

    private static String required(Map<String, String> details, String key) {
        String value = details.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Recorded action is missing " + key);
        }
        return value;
    }

    private static int integer(Map<String, String> details, String key) {
        return integer(details, key, Integer.MIN_VALUE);
    }

    private static int integer(Map<String, String> details, String key, int fallback) {
        String value = details.get(key);
        if (value == null && fallback != Integer.MIN_VALUE) {
            return fallback;
        }
        try {
            return Integer.parseInt(required(details, key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid " + key + ": " + value);
        }
    }

    private static double decimal(Map<String, String> details, String key, double fallback) {
        String value = details.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid " + key + ": " + value);
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported " + label + " " + value);
        }
    }

    record ClickInput(ContainerInput input, byte button) {
    }

    public static List<String> list(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".yml"))
                .map(name -> name.substring(0, name.length() - 4))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public static boolean delete(@NotNull Path directory, @NotNull String scenarioName) throws IOException {
        validateName(scenarioName);
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path scenarioFile = normalizedDirectory.resolve(scenarioName + ".yml").normalize();
        if (!scenarioFile.startsWith(normalizedDirectory)) {
            throw new IOException("Unsafe scenario path");
        }
        return Files.deleteIfExists(scenarioFile);
    }

    static void save(Path file, Scenario scenario) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 2);
        yaml.set("name", scenario.name());
        yaml.set("player", scenario.playerName());
        yaml.set("breakpoints", scenario.breakpoints().stream().sorted().toList());
        List<Map<String, Object>> steps = scenario.steps().stream().map(step -> {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("type", step.type());
            serialized.put("value", step.value());
            serialized.put("details", step.details());
            return serialized;
        }).toList();
        yaml.set("steps", steps);
        writeState(yaml, "initial", scenario.initial());
        writeState(yaml, "expected", scenario.expected());
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        yaml.save(file.toFile());
    }

    private static void writeState(YamlConfiguration yaml, String path, PlayerState state) {
        yaml.set(path + ".world", state.world());
        yaml.set(path + ".x", state.x());
        yaml.set(path + ".y", state.y());
        yaml.set(path + ".z", state.z());
        yaml.set(path + ".inventory", state.inventory());
    }

    @SuppressWarnings("unchecked")
    static Scenario load(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (Exception exception) {
            throw new IOException("Cannot read scenario " + file.getFileName(), exception);
        }
        List<Step> steps = new ArrayList<>();
        for (Map<?, ?> serialized : yaml.getMapList("steps")) {
            Object rawDetails = serialized.get("details");
            Map<String, String> details = new LinkedHashMap<>();
            if (rawDetails instanceof Map<?, ?> values) {
                values.forEach((key, value) -> details.put(String.valueOf(key), String.valueOf(value)));
            }
            steps.add(new Step(String.valueOf(serialized.get("type")), String.valueOf(serialized.get("value")), details));
        }
        PlayerState initial = readState(yaml, "initial");
        PlayerState expected = readState(yaml, "expected");
        Set<Integer> breakpoints = new LinkedHashSet<>(yaml.getIntegerList("breakpoints"));
        breakpoints.removeIf(index -> index < 0 || index >= steps.size());
        return new Scenario(yaml.getString("name", file.getFileName().toString()), yaml.getString("player", ""), steps, initial, expected, breakpoints);
    }

    static Scenario setBreakpoint(Path file, int stepIndex, boolean enabled) throws IOException {
        Scenario scenario = load(file);
        if (stepIndex < 0 || stepIndex >= scenario.steps().size()) {
            throw new IllegalArgumentException("Scenario step is out of range: " + (stepIndex + 1));
        }
        Set<Integer> breakpoints = new LinkedHashSet<>(scenario.breakpoints());
        if (enabled) {
            breakpoints.add(stepIndex);
        } else {
            breakpoints.remove(stepIndex);
        }
        Scenario updated = new Scenario(scenario.name(), scenario.playerName(), scenario.steps(), scenario.initial(), scenario.expected(), breakpoints);
        save(file, updated);
        return updated;
    }

    private static PlayerState readState(YamlConfiguration yaml, String path) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        var inventorySection = yaml.getConfigurationSection(path + ".inventory");
        if (inventorySection != null) {
            for (String key : inventorySection.getKeys(false)) {
                inventory.put(key, inventorySection.getInt(key));
            }
        }
        return new PlayerState(
            yaml.getString(path + ".world", ""),
            yaml.getDouble(path + ".x"),
            yaml.getDouble(path + ".y"),
            yaml.getDouble(path + ".z"),
            inventory
        );
    }

    private static void validateName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Scenario name must use 1-64 letters, numbers, '.', '_' or '-'.");
        }
    }

    private record Recording(UUID playerId, String playerName, PlayerState initial, List<Step> steps) {
    }

    public record RecordingStatus(UUID playerId, String playerName, int stepCount) {
    }

    public record Step(String type, String value, Map<String, String> details) {

        public Step {
            details = Map.copyOf(details);
        }
    }

    public record Scenario(String name, String playerName, List<Step> steps, PlayerState initial, PlayerState expected, Set<Integer> breakpoints) {

        public Scenario(String name, String playerName, List<Step> steps, PlayerState initial, PlayerState expected) {
            this(name, playerName, steps, initial, expected, Set.of());
        }

        public Scenario {
            steps = List.copyOf(steps);
            breakpoints = Set.copyOf(breakpoints);
        }
    }

    public record PlayerState(String world, double x, double y, double z, Map<String, Integer> inventory) {

        public PlayerState {
            inventory = Map.copyOf(inventory);
        }

        static PlayerState capture(Player player) {
            Location location = player.getLocation();
            Map<String, Integer> inventory = new java.util.TreeMap<>();
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    inventory.merge(item.getType().name(), item.getAmount(), Integer::sum);
                }
            }
            return new PlayerState(player.getWorld().getName(), location.getX(), location.getY(), location.getZ(), inventory);
        }

        List<String> compare(Player player) {
            PlayerState actual = capture(player);
            List<String> failures = new ArrayList<>();
            if (!this.world.equals(actual.world)) {
                failures.add("Expected world " + this.world + " but was " + actual.world);
            }
            double distanceSquared = square(this.x - actual.x) + square(this.y - actual.y) + square(this.z - actual.z);
            if (distanceSquared > 0.75D * 0.75D) {
                failures.add(String.format(Locale.ROOT, "Expected location %.2f, %.2f, %.2f but was %.2f, %.2f, %.2f", this.x, this.y, this.z, actual.x, actual.y, actual.z));
            }
            if (!this.inventory.equals(actual.inventory)) {
                failures.add("Expected inventory " + this.inventory + " but was " + actual.inventory);
            }
            return failures;
        }

        List<String> apply(Player player) {
            List<String> failures = new ArrayList<>();
            org.bukkit.World targetWorld = Bukkit.getWorld(this.world);
            if (targetWorld == null || !player.teleport(new Location(targetWorld, this.x, this.y, this.z))) {
                failures.add("Could not restore initial location in world " + this.world);
            }
            player.getInventory().clear();
            for (Map.Entry<String, Integer> entry : this.inventory.entrySet()) {
                try {
                    org.bukkit.Material material = org.bukkit.Material.valueOf(entry.getKey());
                    int remaining = entry.getValue();
                    while (remaining > 0) {
                        int amount = Math.min(material.getMaxStackSize(), remaining);
                        player.getInventory().addItem(new ItemStack(material, amount));
                        remaining -= amount;
                    }
                } catch (IllegalArgumentException exception) {
                    failures.add("Unknown material in initial inventory: " + entry.getKey());
                }
            }
            return failures;
        }

        private static double square(double value) {
            return value * value;
        }
    }

    public record ReplayStepResult(int stepIndex, String type, String value, String status, String message) {
    }

    public static final class ReplaySession {

        private final Scenario scenario;
        private final Player player;
        private final List<String> failures = new ArrayList<>();
        private final List<ReplayStepResult> stepResults = new ArrayList<>();
        private int nextStepIndex;
        private int commands;
        private int replayedActions;
        private int skippedSteps;
        private boolean completed;

        private ReplaySession(Scenario scenario, Player player) {
            this.scenario = scenario;
            this.player = player;
            player.closeInventory();
            this.failures.addAll(scenario.initial().apply(player));
        }

        public ReplayProgress advance(@NotNull Set<Integer> breakpoints, boolean stepOverCurrentBreakpoint) {
            if (this.completed) {
                return new ReplayProgress(true, this.nextStepIndex, this.result());
            }
            boolean ignoreBreakpoint = stepOverCurrentBreakpoint;
            while (this.nextStepIndex < this.scenario.steps().size()) {
                if (breakpoints.contains(this.nextStepIndex) && !ignoreBreakpoint) {
                    return new ReplayProgress(false, this.nextStepIndex, this.result());
                }
                ignoreBreakpoint = false;
                this.executeCurrentStep();
                this.nextStepIndex++;
            }
            this.failures.addAll(this.scenario.expected().compare(this.player));
            this.completed = true;
            return new ReplayProgress(true, this.nextStepIndex, this.result());
        }

        public Scenario scenario() {
            return this.scenario;
        }

        private void executeCurrentStep() {
            int index = this.nextStepIndex;
            Step step = this.scenario.steps().get(index);
            try {
                switch (step.type()) {
                    case "COMMAND" -> {
                        this.commands++;
                        String command = step.value().startsWith("/") ? step.value().substring(1) : step.value();
                        if (!Bukkit.dispatchCommand(this.player, command)) {
                            throw new IllegalStateException("Command returned false: /" + command);
                        }
                        this.stepResults.add(new ReplayStepResult(index, step.type(), step.value(), "REPLAYED", "/" + command));
                    }
                    case "PLAYER_INTERACT" -> {
                        replayPlayerInteract(step, this.player);
                        this.replayedActions++;
                        this.stepResults.add(new ReplayStepResult(index, step.type(), step.value(), "REPLAYED", describeInteraction(step)));
                    }
                    case "INVENTORY_CLICK" -> {
                        String message = replayInventoryClick(step, this.player);
                        this.replayedActions++;
                        this.stepResults.add(new ReplayStepResult(index, step.type(), step.value(), "REPLAYED", message));
                    }
                    default -> {
                        this.skippedSteps++;
                        this.stepResults.add(new ReplayStepResult(index, step.type(), step.value(), "OBSERVED", "Informational event; no input to replay"));
                    }
                }
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                this.failures.add("Step " + (index + 1) + " (" + step.type() + ") failed: " + message);
                this.stepResults.add(new ReplayStepResult(index, step.type(), step.value(), "FAILED", message));
            }
        }

        private ReplayResult result() {
            return new ReplayResult(
                this.scenario.name(), this.commands, this.replayedActions, this.skippedSteps, List.copyOf(this.stepResults), List.copyOf(this.failures)
            );
        }
    }

    public record ReplayProgress(boolean completed, int nextStepIndex, ReplayResult result) {
    }

    public record ReplayResult(
        String scenarioName,
        int commandsExecuted,
        int actionsReplayed,
        int observationsSkipped,
        List<ReplayStepResult> steps,
        List<String> failures
    ) {

        public ReplayResult {
            steps = List.copyOf(steps);
            failures = List.copyOf(failures);
        }

        public boolean successful() {
            return this.failures.isEmpty();
        }
    }
}
