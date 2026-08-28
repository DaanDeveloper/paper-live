package io.papermc.paper.plugin;

import io.papermc.paper.plugin.provider.configuration.PaperPluginMeta;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/** Validates source and installed plugin YAML and tracks the last known-good configuration. */
public final class PaperLiveConfigGuard {

    private static final List<String> DESCRIPTORS = List.of("plugin.yml", "paper-plugin.yml");
    private static final String BASELINE_DIRECTORY = "config-guard-baseline";

    private PaperLiveConfigGuard() {
    }

    public static @NotNull Inspection inspect(@NotNull Path pluginDirectory) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<ConfigChange> changes = new ArrayList<>();
        for (Path project : projectDirectories(pluginDirectory)) {
            diagnostics.addAll(validateProject(project));
            changes.addAll(findChanges(pluginDirectory, project));
        }
        for (Path dataDirectory : installedPluginDirectories(pluginDirectory)) {
            String pluginName = installedPluginName(dataDirectory);
            diagnostics.addAll(validateConfigurationDirectory(pluginName, dataDirectory));
            changes.addAll(findChanges(pluginName, dataDirectory, installedBaselineRoot(pluginDirectory).resolve(dataDirectory.getFileName().toString())));
        }
        return new Inspection(List.copyOf(diagnostics), List.copyOf(changes));
    }

    static @NotNull List<Diagnostic> validateProject(@NotNull Path projectDirectory) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Path resources = projectDirectory.resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return List.of(new Diagnostic(
                projectDirectory.getFileName().toString(),
                resources,
                0,
                0,
                Severity.WARNING,
                "No src/main/resources directory; source YAML could not be checked"
            ));
        }

        List<Path> yamlFiles = yamlFiles(resources);
        if (yamlFiles.stream().noneMatch(file -> file.getParent().equals(resources) && isDescriptor(file))) {
            diagnostics.add(new Diagnostic(
                projectDirectory.getFileName().toString(),
                resources,
                0,
                0,
                Severity.WARNING,
                "No plugin.yml or paper-plugin.yml found in src/main/resources"
            ));
        }
        for (Path yamlFile : yamlFiles) {
            Diagnostic failure = validateYaml(projectDirectory.getFileName().toString(), resources, yamlFile);
            if (failure != null) {
                diagnostics.add(failure);
            }
        }
        return List.copyOf(diagnostics);
    }

    static @NotNull List<Diagnostic> validateConfigurationDirectory(@NotNull String pluginName, @NotNull Path configurationDirectory) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Path yamlFile : yamlFiles(configurationDirectory)) {
            Diagnostic failure = validateYaml(pluginName, configurationDirectory, yamlFile);
            if (failure != null) {
                diagnostics.add(failure);
            }
        }
        return List.copyOf(diagnostics);
    }

    public static boolean captureSuccessfulBaseline(@NotNull Path pluginDirectory) {
        Inspection inspection = inspect(pluginDirectory);
        if (!inspection.valid()) {
            return false;
        }

        Path baselineRoot = baselineRoot(pluginDirectory);
        try {
            for (Path project : projectDirectories(pluginDirectory)) {
                Path resources = project.resolve("src").resolve("main").resolve("resources");
                Path projectBaseline = baselineRoot.resolve(project.getFileName().toString()).normalize();
                captureRoot(resources, projectBaseline, baselineRoot);
            }
            Path installedBaseline = installedBaselineRoot(pluginDirectory);
            for (Path dataDirectory : installedPluginDirectories(pluginDirectory)) {
                captureRoot(dataDirectory, installedBaseline.resolve(dataDirectory.getFileName().toString()), baselineRoot);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Diagnostic validateYaml(String projectName, Path resources, Path yamlFile) {
        try {
            String fileName = yamlFile.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean rootDescriptor = yamlFile.getParent().equals(resources);
            if (rootDescriptor && fileName.equals("plugin.yml")) {
                try (var input = Files.newInputStream(yamlFile)) {
                    new PluginDescriptionFile(input);
                }
            } else if (rootDescriptor && fileName.equals("paper-plugin.yml")) {
                try (BufferedReader reader = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
                    PaperPluginMeta.create(reader);
                }
            } else {
                YamlConfiguration configuration = new YamlConfiguration();
                configuration.load(yamlFile.toFile());
            }
            return null;
        } catch (Exception exception) {
            Location location = errorLocation(exception);
            return new Diagnostic(projectName, yamlFile, location.line(), location.column(), Severity.ERROR, rootMessage(exception));
        }
    }

    private static List<ConfigChange> findChanges(Path pluginDirectory, Path project) {
        Path resources = project.resolve("src").resolve("main").resolve("resources");
        Path baseline = baselineRoot(pluginDirectory).resolve(project.getFileName().toString()).normalize();
        return findChanges(project.getFileName().toString(), resources, baseline);
    }

    private static List<ConfigChange> findChanges(String owner, Path root, Path baseline) {
        Map<String, Path> currentFiles = relativeYamlFiles(root);
        Map<String, Path> baselineFiles = relativeYamlFiles(baseline);
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(currentFiles.keySet());
        paths.addAll(baselineFiles.keySet());

        List<ConfigChange> changes = new ArrayList<>();
        for (String relativePath : paths) {
            Path current = currentFiles.get(relativePath);
            Path previous = baselineFiles.get(relativePath);
            Change type;
            if (previous == null) {
                type = Change.ADDED;
            } else if (current == null) {
                type = Change.REMOVED;
            } else if (!digest(current).equals(digest(previous))) {
                type = Change.MODIFIED;
            } else {
                type = Change.UNCHANGED;
            }
            changes.add(new ConfigChange(owner, relativePath, type, current, previous));
        }
        changes.sort(Comparator.comparing(ConfigChange::project, String.CASE_INSENSITIVE_ORDER).thenComparing(ConfigChange::relativePath));
        return changes;
    }

    private static Map<String, Path> relativeYamlFiles(Path root) {
        Map<String, Path> files = new HashMap<>();
        for (Path file : yamlFiles(root)) {
            files.put(root.relativize(file).toString().replace('\\', '/'), file);
        }
        return files;
    }

    private static List<Path> yamlFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                .filter(PaperLiveConfigGuard::isYaml)
                .sorted()
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static List<Path> projectDirectories(Path pluginDirectory) {
        return PaperLiveProjectCompiler.findProjectDirectories(pluginDirectory.resolve("PaperLive").resolve("projects"));
    }

    private static List<Path> installedPluginDirectories(Path pluginDirectory) {
        if (!Files.isDirectory(pluginDirectory)) {
            return List.of();
        }
        try (var directories = Files.list(pluginDirectory)) {
            return directories
                .filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .filter(path -> !path.getFileName().toString().equalsIgnoreCase("PaperLive"))
                .filter(path -> !yamlFiles(path).isEmpty())
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static String installedPluginName(Path dataDirectory) {
        return "Installed: " + dataDirectory.getFileName();
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static boolean isDescriptor(Path path) {
        return DESCRIPTORS.contains(path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static Path baselineRoot(Path pluginDirectory) {
        return pluginDirectory.resolve(".paperlive-runtime").resolve(BASELINE_DIRECTORY).toAbsolutePath().normalize();
    }

    private static Path installedBaselineRoot(Path pluginDirectory) {
        return baselineRoot(pluginDirectory).resolve("installed-plugins").normalize();
    }

    private static void captureRoot(Path sourceRoot, Path targetRoot, Path allowedRoot) throws IOException {
        Set<Path> expectedTargets = new LinkedHashSet<>();
        for (Path source : yamlFiles(sourceRoot)) {
            Path relative = sourceRoot.relativize(source);
            Path target = targetRoot.resolve(relative).toAbsolutePath().normalize();
            if (!target.startsWith(allowedRoot)) {
                throw new IOException("Unsafe Config Guard baseline path");
            }
            expectedTargets.add(target);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        for (Path stale : yamlFiles(targetRoot)) {
            if (!expectedTargets.contains(stale.toAbsolutePath().normalize())) {
                Files.deleteIfExists(stale);
            }
        }
    }

    private static String digest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "unreadable:" + path;
        }
    }

    private static Location errorLocation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
                return new Location(marked.getProblemMark().getLine() + 1, marked.getProblemMark().getColumn() + 1);
            }
        }
        return new Location(0, 0);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
    }

    public record Inspection(List<Diagnostic> diagnostics, List<ConfigChange> changes) {

        public Inspection {
            diagnostics = List.copyOf(diagnostics);
            changes = List.copyOf(changes);
        }

        public boolean valid() {
            return this.diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
        }
    }

    public record Diagnostic(String project, Path file, int line, int column, Severity severity, String message) {
    }

    public record ConfigChange(String project, String relativePath, Change change, Path currentFile, Path baselineFile) {
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public enum Change {
        ADDED,
        MODIFIED,
        REMOVED,
        UNCHANGED
    }

    private record Location(int line, int column) {
    }
}
