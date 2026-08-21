package io.papermc.paper.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Builds conventional plugin source projects placed in PaperLive's development directory.
 *
 * <p>Projects remain regular Gradle or Maven projects. PaperLive only invokes the project's own
 * wrapper, verifies that its output is a Bukkit plugin JAR, and places that JAR in an isolated
 * runtime directory for the normal Paper plugin loader.</p>
 */
final class PaperLiveProjectCompiler {

    private static final String PAPERLIVE_DIRECTORY = "PaperLive";
    private static final String PROJECTS_DIRECTORY = "projects";
    private static final String RUNTIME_DIRECTORY = ".paperlive-runtime";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final AtomicBoolean SKIP_NEXT_COMPILATION = new AtomicBoolean();
    private static final Pattern PLUGIN_NAME = Pattern.compile("^\\s*name\\s*:\\s*['\\\"]?([^\\s'\\\"#]+)");
    private static final Pattern PLUGIN_MAIN_CLASS = Pattern.compile("^\\s*main\\s*:\\s*['\\\"]?([^\\s'\\\"#]+)");
    private static final List<String> PLUGIN_DESCRIPTORS = List.of("plugin.yml", "paper-plugin.yml");

    private PaperLiveProjectCompiler() {
    }

    /**
     * Builds each supported source project and returns the directory containing its runtime JARs.
     *
     * @param pluginDirectory Paper's configured plugin directory
     * @param logger the server logger used for build diagnostics
     * @return the runtime directory when it contains built project JARs, or {@code null} when no
     *     PaperLive project directory exists
     */
    static @Nullable Path compileProjects(@NotNull Path pluginDirectory, @NotNull Logger logger) {
        CompilationResult compilation = compileProjectsDetailed(pluginDirectory, logger);
        if (!compilation.successful() || !prepareRuntimeJars(compilation, logger)) {
            return null;
        }

        return compilation.runtimeDirectory();
    }

    /**
     * Builds source projects and reports whether every build produced a loadable plugin JAR.
     *
     * @param pluginDirectory Paper's configured plugin directory
     * @param logger the server logger used for build diagnostics
     * @return the compilation result
     */
    static @NotNull CompilationResult compileProjectsDetailed(@NotNull Path pluginDirectory, @NotNull Logger logger) {
        Path projectsDirectory = pluginDirectory.resolve(PAPERLIVE_DIRECTORY).resolve(PROJECTS_DIRECTORY);

        try {
            Files.createDirectories(projectsDirectory);
        } catch (IOException exception) {
            logger.error("[PaperLive] Cannot create the projects directory {}", projectsDirectory, exception);
            return new CompilationResult(null, List.of(), List.of(projectsDirectory.getFileName().toString()));
        }

        Path runtimeDirectory = pluginDirectory.resolve(RUNTIME_DIRECTORY);

        try {
            Files.createDirectories(runtimeDirectory);
        } catch (IOException exception) {
            logger.error("[PaperLive] Cannot create the runtime directory {}", runtimeDirectory, exception);
            return new CompilationResult(null, List.of(), List.of(runtimeDirectory.getFileName().toString()));
        }

        if (SKIP_NEXT_COMPILATION.compareAndSet(true, false)) {
            return new CompilationResult(runtimeDirectory, List.of(), List.of());
        }

        List<String> failedProjects = new ArrayList<>();
        List<BuildArtifact> buildArtifacts = new ArrayList<>();

        for (Path projectDirectory : findProjectDirectories(projectsDirectory)) {
            BuildArtifact buildArtifact = compileProject(projectDirectory, runtimeDirectory, logger);
            if (buildArtifact == null) {
                failedProjects.add(projectDirectory.getFileName().toString());
            } else {
                buildArtifacts.add(buildArtifact);
            }
        }

        return new CompilationResult(runtimeDirectory, List.copyOf(buildArtifacts), List.copyOf(failedProjects));
    }

    static void skipNextCompilation() {
        SKIP_NEXT_COMPILATION.set(true);
    }

    private static @Nullable BuildArtifact compileProject(@NotNull Path projectDirectory, @NotNull Path runtimeDirectory, @NotNull Logger logger) {
        BuildSystem buildSystem = BuildSystem.find(projectDirectory);

        if (buildSystem == null) {
            return null;
        }

        String projectName = projectDirectory.getFileName().toString();
        Path logFile = runtimeDirectory.resolve("paperlive-" + projectName + ".build.log");
        logger.info("[PaperLive] Building source project '{}'", projectName);

        if (!buildSystem.run(projectDirectory, logFile, logger)) {
            logger.error("[PaperLive] Build failed for '{}'. See {}", projectName, logFile);
            return null;
        }

        Path pluginJar = findPluginJar(projectDirectory, buildSystem.outputDirectory(projectDirectory));
        if (pluginJar == null) {
            logger.error("[PaperLive] Build succeeded for '{}', but no JAR with plugin.yml was found in {}", projectName, buildSystem.outputDirectory(projectDirectory));
            return null;
        }

        String pluginName = findPluginName(pluginJar);
        if (pluginName == null) {
            logger.error("[PaperLive] Build succeeded for '{}', but its plugin descriptor has no name", projectName);
            return null;
        }

        return new BuildArtifact(projectName, pluginName, pluginJar);
    }

    /**
     * Copies completed project JARs after any loaded plugin classloaders have been closed.
     *
     * <p>On Windows, a loaded JAR cannot be replaced. Builds therefore finish before this method
     * is called, while refreshes defer this copy until the main thread has quiesced plugins.</p>
     *
     * @param compilation successful project compilation with JARs ready to install
     * @param logger the server logger used for install diagnostics
     * @return whether every JAR was copied to the runtime directory
     */
    static boolean prepareRuntimeJars(@NotNull CompilationResult compilation, @NotNull Logger logger) {
        if (!compilation.successful() || compilation.runtimeDirectory() == null) {
            return false;
        }

        for (BuildArtifact buildArtifact : compilation.buildArtifacts()) {
            Path runtimeJar = compilation.runtimeDirectory().resolve("paperlive-" + buildArtifact.projectName() + ".jar");

            try {
                Files.copy(buildArtifact.pluginJar(), runtimeJar, StandardCopyOption.REPLACE_EXISTING);
                logger.info("[PaperLive] Prepared '{}' from {}", buildArtifact.projectName(), buildArtifact.pluginJar().getFileName());
            } catch (IOException exception) {
                logger.error("[PaperLive] Cannot prepare runtime JAR for '{}'", buildArtifact.projectName(), exception);
                return false;
            }
        }

        return true;
    }

    /**
     * Finds source projects that explicitly declare a supported build system.
     *
     * @param projectsDirectory the PaperLive source-project directory
     * @return deterministic project directories that can be built
     */
    static @NotNull List<Path> findProjectDirectories(@NotNull Path projectsDirectory) {
        if (!Files.isDirectory(projectsDirectory)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.list(projectsDirectory)) {
            return paths
                .filter(Files::isDirectory)
                .filter(projectDirectory -> BuildSystem.find(projectDirectory) != null)
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Selects the newest complete JAR that contains the standard Bukkit plugin descriptor.
     *
     * @param outputDirectory the Gradle or Maven output directory
     * @return a plugin JAR, or {@code null} when no valid output exists
     */
    static @Nullable Path findPluginJar(@NotNull Path projectDirectory, @NotNull Path outputDirectory) {
        Stream<Path> paths;

        try {
            // Single-module builds use build/libs or target directly. Multi-module projects
            // publish from child modules, so find the newest valid Bukkit plugin JAR anywhere
            // in the project tree instead of assuming a specific module name or layout.
            paths = Files.isDirectory(outputDirectory)
                ? Stream.concat(Files.list(outputDirectory), Files.walk(projectDirectory, 6))
                : Files.walk(projectDirectory, 6);
        } catch (IOException exception) {
            return null;
        }

        try (paths) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .filter(PaperLiveProjectCompiler::containsPluginDescriptor)
                .max(Comparator.comparing(PaperLiveProjectCompiler::lastModifiedTime))
                .orElse(null);
        }
    }

    private static boolean containsPluginDescriptor(@NotNull Path jarFile) {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            for (String descriptorName : PLUGIN_DESCRIPTORS) {
                JarEntry pluginDescriptor = jar.getJarEntry(descriptorName);

                if (pluginDescriptor != null && containsDeclaredMainClass(jar, pluginDescriptor)) {
                    return true;
                }
            }

            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private static @Nullable String findPluginName(@NotNull Path jarFile) {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            for (String descriptorName : PLUGIN_DESCRIPTORS) {
                JarEntry descriptor = jar.getJarEntry(descriptorName);
                if (descriptor == null) {
                    continue;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(descriptor), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher pluginName = PLUGIN_NAME.matcher(line);
                        if (pluginName.find()) {
                            return pluginName.group(1);
                        }
                    }
                }
            }
        } catch (IOException exception) {
            return null;
        }

        return null;
    }

    /**
     * Rejects archives that have acquired {@code plugin.yml} before Maven or Gradle has finished
     * writing the main class. This prevents a partially written build output from being installed
     * into the PaperLive runtime directory on Windows.
     */
    private static boolean containsDeclaredMainClass(@NotNull JarFile jar, @NotNull JarEntry pluginDescriptor) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(pluginDescriptor), StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                Matcher mainClass = PLUGIN_MAIN_CLASS.matcher(line);

                if (mainClass.find()) {
                    return jar.getJarEntry(mainClass.group(1).replace('.', '/') + ".class") != null;
                }
            }
        }

        return false;
    }

    private static @NotNull FileTime lastModifiedTime(@NotNull Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    private enum BuildSystem {
        GRADLE("gradlew.bat", "gradlew", "build", "build/libs"),
        MAVEN("mvnw.cmd", "mvnw", "package", "target");

        private final String windowsWrapper;
        private final String unixWrapper;
        private final String task;
        private final String outputDirectory;

        BuildSystem(String windowsWrapper, String unixWrapper, String task, String outputDirectory) {
            this.windowsWrapper = windowsWrapper;
            this.unixWrapper = unixWrapper;
            this.task = task;
            this.outputDirectory = outputDirectory;
        }

        static @Nullable BuildSystem find(@NotNull Path projectDirectory) {
            if (Files.isRegularFile(projectDirectory.resolve("build.gradle.kts")) || Files.isRegularFile(projectDirectory.resolve("build.gradle"))) {
                return GRADLE;
            }

            if (Files.isRegularFile(projectDirectory.resolve("pom.xml"))) {
                return MAVEN;
            }

            return null;
        }

        boolean run(@NotNull Path projectDirectory, @NotNull Path logFile, @NotNull Logger logger) {
            String wrapper = isWindows() ? this.windowsWrapper : this.unixWrapper;
            Path wrapperPath = projectDirectory.resolve(wrapper);

            if (!Files.isRegularFile(wrapperPath)) {
                logger.error("[PaperLive] {} project '{}' needs its own {} wrapper", this.name().toLowerCase(Locale.ROOT), projectDirectory.getFileName(), wrapper);
                return false;
            }

            List<String> command = new ArrayList<>();

            if (isWindows()) {
                command.add("cmd.exe");
                command.add("/d");
                command.add("/c");
                command.add(wrapper);
            } else {
                command.add(wrapperPath.toAbsolutePath().toString());
            }

            command.add(this.task);

            try {
                Process process = new ProcessBuilder(command)
                    .directory(projectDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

                if (!process.waitFor(BUILD_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    logger.error("[PaperLive] {} build timed out after {} minutes", projectDirectory.getFileName(), BUILD_TIMEOUT.toMinutes());
                    return false;
                }

                return process.exitValue() == 0;
            } catch (IOException exception) {
                logger.error("[PaperLive] Cannot start the build for {}", projectDirectory.getFileName(), exception);
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.error("[PaperLive] Build interrupted for {}", projectDirectory.getFileName(), exception);
                return false;
            }
        }

        @NotNull Path outputDirectory(@NotNull Path projectDirectory) {
            return projectDirectory.resolve(this.outputDirectory);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }

    /**
     * Result of compiling every discovered PaperLive source project.
     *
     * @param runtimeDirectory directory containing prepared JARs
     * @param failedProjects projects that could not produce a valid plugin JAR
     */
    record CompilationResult(@Nullable Path runtimeDirectory, @NotNull List<BuildArtifact> buildArtifacts, @NotNull List<String> failedProjects) {

        boolean successful() {
            return this.runtimeDirectory != null && this.failedProjects.isEmpty();
        }

        @NotNull List<String> pluginNames() {
            return this.buildArtifacts.stream().map(BuildArtifact::pluginName).toList();
        }

        @NotNull List<Path> runtimeJars() {
            if (this.runtimeDirectory == null) {
                return List.of();
            }
            return this.buildArtifacts.stream()
                .map(artifact -> this.runtimeDirectory.resolve("paperlive-" + artifact.projectName() + ".jar"))
                .toList();
        }
    }

    private record BuildArtifact(@NotNull String projectName, @NotNull String pluginName, @NotNull Path pluginJar) {
    }
}
