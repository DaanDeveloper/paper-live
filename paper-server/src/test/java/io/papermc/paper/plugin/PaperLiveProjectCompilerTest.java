package io.papermc.paper.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("Normal")
class PaperLiveProjectCompilerTest {

    @Test
    void findsOnlyProjectsWithSupportedBuildFiles(@TempDir Path directory) throws IOException {
        Path projectsDirectory = Files.createDirectories(directory.resolve("projects"));
        Path gradleProject = Files.createDirectories(projectsDirectory.resolve("Alpha"));
        Path mavenProject = Files.createDirectories(projectsDirectory.resolve("Bravo"));
        Files.createDirectories(projectsDirectory.resolve("NotAProject"));
        Files.createFile(gradleProject.resolve("build.gradle.kts"));
        Files.createFile(mavenProject.resolve("pom.xml"));

        List<Path> projects = PaperLiveProjectCompiler.findProjectDirectories(projectsDirectory);

        assertEquals(List.of(gradleProject, mavenProject), projects);
    }

    @Test
    void findsOnlyJarOutputsContainingPluginYml(@TempDir Path directory) throws IOException {
        Path outputDirectory = Files.createDirectories(directory.resolve("build/libs"));
        Path plainJar = outputDirectory.resolve("plain.jar");
        Path pluginJar = outputDirectory.resolve("plugin.jar");
        createJar(plainJar, "not-plugin.yml");
        createJar(pluginJar, "plugin.yml");

        assertEquals(pluginJar, PaperLiveProjectCompiler.findPluginJar(directory, outputDirectory));
    }

    @Test
    void returnsNullWhenNoPluginJarExists(@TempDir Path directory) throws IOException {
        Path outputDirectory = Files.createDirectories(directory.resolve("build/libs"));
        createJar(outputDirectory.resolve("plain.jar"), "not-plugin.yml");

        assertNull(PaperLiveProjectCompiler.findPluginJar(directory, outputDirectory));
    }

    private static void createJar(Path jarPath, String entryName) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry(entryName));
            if (entryName.equals("plugin.yml")) {
                output.write("name: TestPlugin\nmain: example.TestPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            output.closeEntry();
            if (entryName.equals("plugin.yml")) {
                output.putNextEntry(new JarEntry("example/TestPlugin.class"));
                output.closeEntry();
            }
        }
    }
}
