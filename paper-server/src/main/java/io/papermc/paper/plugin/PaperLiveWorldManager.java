package io.papermc.paper.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Fast, recoverable world creation and reset operations intended for local development servers. */
public final class PaperLiveWorldManager {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> PRESETS = List.of("flat", "normal", "void", "amplified", "large_biomes");

    private PaperLiveWorldManager() {
    }

    public static void execute(@NotNull Consumer<String> output, @NotNull String[] arguments) {
        if (arguments.length < 3 || (!arguments[1].equalsIgnoreCase("create") && !arguments[1].equalsIgnoreCase("reset"))) {
            output.accept("§c[PaperLive] Invalid setup operation.");
            return;
        }

        String operation = arguments[1].toLowerCase(Locale.ROOT);
        String worldName = arguments[2];
        try {
            WorldOptions options = WorldOptions.parse(arguments, 3);
            if (operation.equals("create")) {
                create(output, worldName, options);
            } else {
                reset(output, worldName, options);
            }
        } catch (IllegalArgumentException exception) {
            output.accept("§c[PaperLive] " + exception.getMessage());
        }
    }

    public static @NotNull String presetFor(@NotNull World world) {
        if (world.getGenerator() instanceof EmptyChunkGenerator) {
            return "void";
        }
        return switch (world.getWorldType()) {
            case FLAT -> "flat";
            case AMPLIFIED -> "amplified";
            case LARGE_BIOMES -> "large_biomes";
            default -> "normal";
        };
    }

    private static void create(Consumer<String> output, String worldName, WorldOptions options) {
        validateWorldName(worldName);
        if (Bukkit.getWorld(worldName) != null || Files.exists(legacyWorldPath(worldName)) || Files.exists(storedWorldPath(worldName))) {
            throw new IllegalArgumentException("World '" + worldName + "' bestaat al. Gebruik world reset om hem opnieuw te maken.");
        }
        output.accept("§e[PaperLive] Creating " + options.preset + " world '" + worldName + "'...");
        World world = createWorld(worldName, options, output);
        output.accept("§a[PaperLive] World '" + world.getName() + "' is ready. Seed: §f" + world.getSeed());
        output.accept("§7[PaperLive] Spawn: §f" + formatLocation(world.getSpawnLocation()));
    }

    private static void reset(Consumer<String> output, String worldName, WorldOptions options) {
        if (!options.confirmed) {
            throw new IllegalArgumentException("Reset vervangt de world. Voeg 'confirm' toe; de huidige map wordt eerst geback-upt.");
        }
        World world = Bukkit.getWorld(worldName);
        World primaryWorld = Bukkit.getWorlds().getFirst();
        if (worldName.equalsIgnoreCase(primaryWorld.getName())) {
            throw new IllegalArgumentException("De primaire world kan niet live worden gereset. Gebruik hiervoor een aparte dev-world.");
        }
        if (world == null) {
            throw new IllegalArgumentException("World '" + worldName + "' is niet geladen. Laad hem eerst zodat PaperLive het exacte opslagpad veilig kan bepalen.");
        }

        Path worldPath = world.getWorldPath().toAbsolutePath().normalize();
        validateStoredWorldPath(worldPath);

        Location fallback = primaryWorld.getSpawnLocation();
        for (Player player : List.copyOf(world.getPlayers())) {
            if (!player.teleport(fallback)) {
                throw new IllegalArgumentException("Kon speler '" + player.getName() + "' niet veilig uit de world teleporteren.");
            }
        }
        if (!Bukkit.unloadWorld(world, false)) {
            throw new IllegalArgumentException("World unload werd geweigerd, mogelijk door een plugin.");
        }

        Path backup = null;
        if (Files.exists(worldPath)) {
            backup = backupPath(worldName);
            try {
                Files.createDirectories(backup.getParent());
                Files.move(worldPath, backup, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                try {
                    Files.move(worldPath, backup);
                } catch (IOException failure) {
                    throw new IllegalArgumentException("Kon geen backup maken: " + failure.getMessage());
                }
            }
            output.accept("§e[PaperLive] Backup: §f" + backup.toAbsolutePath());
        }

        output.accept("§e[PaperLive] Recreating " + options.preset + " world '" + worldName + "'...");
        try {
            World recreated = createWorld(worldName, options, output);
            output.accept("§a[PaperLive] World '" + recreated.getName() + "' reset complete. Seed: §f" + recreated.getSeed());
            output.accept("§7[PaperLive] Spawn: §f" + formatLocation(recreated.getSpawnLocation()));
        } catch (RuntimeException failure) {
            restoreBackup(worldPath, backup, output);
            throw failure;
        }
    }

    private static World createWorld(String worldName, WorldOptions options, Consumer<String> output) {
        WorldCreator creator = new WorldCreator(worldName)
            .environment(options.environment)
            .type(options.worldType)
            .generateStructures(options.structures)
            .hardcore(options.hardcore)
            .bonusChest(options.bonusChest);
        if (options.seed != null) {
            creator.seed(options.seed);
        }
        if (options.generatorSettings != null) {
            creator.generatorSettings(options.generatorSettings);
        }
        if (options.preset.equals("void")) {
            creator.generator(new EmptyChunkGenerator());
            creator.generateStructures(false);
        }

        World world = creator.createWorld();
        if (world == null) {
            throw new IllegalStateException("Paper kon de world niet maken.");
        }
        if (options.preset.equals("void")) {
            Location spawn = new Location(world, 0.5, 65.0, 0.5);
            if (options.platform) {
                world.getBlockAt(0, 64, 0).setType(org.bukkit.Material.STONE);
            }
            world.setSpawnLocation(spawn);
            output.accept(options.platform
                ? "§7[PaperLive] Void spawn platform placed at 0, 64, 0."
                : "§7[PaperLive] Void world has no spawn platform (platform=false)."
            );
        }
        return world;
    }

    private static void restoreBackup(Path worldPath, @Nullable Path backup, Consumer<String> output) {
        if (backup == null || !Files.exists(backup) || Files.exists(worldPath)) {
            output.accept("§c[PaperLive] Creation failed; automatic restore was not possible. De backup is behouden.");
            return;
        }
        try {
            Files.move(backup, worldPath);
            output.accept("§e[PaperLive] Creation failed; the previous world folder was restored.");
        } catch (IOException restoreFailure) {
            output.accept("§c[PaperLive] Creation failed and restore failed. Backup: " + backup.toAbsolutePath());
        }
    }

    private static void validateWorldName(String worldName) {
        if (!worldName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || worldName.equals(".") || worldName.equals("..")) {
            throw new IllegalArgumentException("Ongeldige worldnaam. Gebruik 1-64 letters, cijfers, '.', '_' of '-'.");
        }
    }

    private static Path legacyWorldPath(String worldName) {
        validateWorldName(worldName);
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path worldPath = root.resolve(worldName).normalize();
        if (!worldPath.getParent().equals(root)) {
            throw new IllegalArgumentException("Worldpad valt buiten de servermap.");
        }
        return worldPath;
    }

    private static Path storedWorldPath(String worldName) {
        validateWorldName(worldName);
        Path primaryPath = Bukkit.getWorlds().getFirst().getWorldPath().toAbsolutePath().normalize();
        return primaryPath.resolve("dimensions").resolve("minecraft").resolve(worldName.toLowerCase(Locale.ROOT)).normalize();
    }

    private static void validateStoredWorldPath(Path worldPath) {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!worldPath.startsWith(root) || worldPath.equals(root) || worldPath.equals(Bukkit.getWorlds().getFirst().getWorldPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Het opslagpad van deze world is niet veilig voor een live reset.");
        }
    }

    private static Path backupPath(String worldName) {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        String timestamp = BACKUP_TIME.format(LocalDateTime.now());
        Path candidate = root.resolve(".paperlive-world-backups").resolve(worldName + "-" + timestamp);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = root.resolve(".paperlive-world-backups").resolve(worldName + "-" + timestamp + '-' + suffix++);
        }
        return candidate;
    }

    private static String formatLocation(Location location) {
        return String.format(Locale.ROOT, "%s (%.1f, %.1f, %.1f)", location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    private static final class EmptyChunkGenerator extends ChunkGenerator {
    }

    record WorldOptions(
        String preset,
        WorldType worldType,
        World.Environment environment,
        @Nullable Long seed,
        boolean structures,
        boolean hardcore,
        boolean bonusChest,
        boolean platform,
        @Nullable String generatorSettings,
        boolean confirmed
    ) {

        static WorldOptions parse(String[] arguments, int offset) {
            int optionOffset = offset;
            String preset = "normal";
            if (arguments.length > offset && !arguments[offset].contains("=") && !arguments[offset].equalsIgnoreCase("confirm")) {
                preset = arguments[offset].toLowerCase(Locale.ROOT);
                optionOffset++;
            }
            WorldType worldType = switch (preset) {
                case "normal", "void" -> WorldType.NORMAL;
                case "flat" -> WorldType.FLAT;
                case "amplified" -> WorldType.AMPLIFIED;
                case "large_biomes" -> WorldType.LARGE_BIOMES;
                default -> throw new IllegalArgumentException("Onbekende preset '" + preset + "'. Kies: " + String.join(", ", PRESETS));
            };

            World.Environment environment = World.Environment.NORMAL;
            Long seed = null;
            boolean structures = !preset.equals("void");
            boolean hardcore = false;
            boolean bonusChest = false;
            boolean platform = preset.equals("void");
            String settings = null;
            boolean confirmed = false;
            for (int index = optionOffset; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument.equalsIgnoreCase("confirm")) {
                    confirmed = true;
                    continue;
                }
                int separator = argument.indexOf('=');
                if (separator < 1) {
                    throw new IllegalArgumentException("Ongeldige optie '" + argument + "'. Gebruik key=value.");
                }
                String key = argument.substring(0, separator).toLowerCase(Locale.ROOT);
                String value = argument.substring(separator + 1);
                switch (key) {
                    case "seed" -> seed = value.equalsIgnoreCase("random") ? null : parseLong("seed", value);
                    case "environment", "env" -> environment = parseEnvironment(value);
                    case "structures" -> structures = parseBoolean(key, value);
                    case "hardcore" -> hardcore = parseBoolean(key, value);
                    case "bonus-chest", "bonuschest" -> bonusChest = parseBoolean(key, value);
                    case "platform" -> platform = parseBoolean(key, value);
                    case "settings" -> settings = value;
                    default -> throw new IllegalArgumentException("Onbekende optie '" + key + "'.");
                }
            }
            return new WorldOptions(preset, worldType, environment, seed, structures, hardcore, bonusChest, platform, settings, confirmed);
        }

        private static long parseLong(String key, String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(key + " moet een geheel getal of 'random' zijn.");
            }
        }

        private static boolean parseBoolean(String key, String value) {
            if (value.equalsIgnoreCase("true")) {
                return true;
            }
            if (value.equalsIgnoreCase("false")) {
                return false;
            }
            throw new IllegalArgumentException(key + " moet true of false zijn.");
        }

        private static World.Environment parseEnvironment(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "normal", "overworld" -> World.Environment.NORMAL;
                case "nether" -> World.Environment.NETHER;
                case "the_end", "end" -> World.Environment.THE_END;
                default -> throw new IllegalArgumentException("environment moet normal, nether of the_end zijn.");
            };
        }
    }
}
