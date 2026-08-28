package io.papermc.paper.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperLiveConfigGuardTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesYamlAndTracksLastSuccessfulChanges() throws IOException {
        Path pluginDirectory = this.temporaryDirectory.resolve("plugins");
        Path resources = createProject(pluginDirectory, "shop");
        Files.writeString(resources.resolve("plugin.yml"), "name: Shop\nversion: 1.0\nmain: example.Shop\n");
        Path config = resources.resolve("config.yml");
        Files.writeString(config, "price: 10\nenabled: true\n");

        PaperLiveConfigGuard.Inspection initial = PaperLiveConfigGuard.inspect(pluginDirectory);
        assertTrue(initial.valid());
        assertTrue(initial.changes().stream().allMatch(change -> change.change() == PaperLiveConfigGuard.Change.ADDED));
        assertTrue(PaperLiveConfigGuard.captureSuccessfulBaseline(pluginDirectory));
        assertTrue(PaperLiveConfigGuard.inspect(pluginDirectory).changes().stream()
            .allMatch(change -> change.change() == PaperLiveConfigGuard.Change.UNCHANGED));

        Files.writeString(config, "price: 25\nenabled: true\n");

        PaperLiveConfigGuard.ConfigChange change = PaperLiveConfigGuard.inspect(pluginDirectory).changes().stream()
            .filter(candidate -> candidate.relativePath().equals("config.yml"))
            .findFirst()
            .orElseThrow();
        assertEquals(PaperLiveConfigGuard.Change.MODIFIED, change.change());

        Files.delete(config);
        assertTrue(PaperLiveConfigGuard.inspect(pluginDirectory).changes().stream()
            .anyMatch(candidate -> candidate.relativePath().equals("config.yml") && candidate.change() == PaperLiveConfigGuard.Change.REMOVED));
        assertTrue(PaperLiveConfigGuard.captureSuccessfulBaseline(pluginDirectory));
        assertTrue(PaperLiveConfigGuard.inspect(pluginDirectory).changes().stream()
            .noneMatch(candidate -> candidate.relativePath().equals("config.yml")));
    }

    @Test
    void reportsYamlLocationAndRefusesInvalidBaseline() throws IOException {
        Path pluginDirectory = this.temporaryDirectory.resolve("plugins");
        Path resources = createProject(pluginDirectory, "broken");
        Files.writeString(resources.resolve("plugin.yml"), "name: Broken\nversion: 1.0\nmain: example.Broken\n");
        Files.writeString(resources.resolve("config.yml"), "settings:\n  valid: true\n broken: indentation\n");

        PaperLiveConfigGuard.Inspection inspection = PaperLiveConfigGuard.inspect(pluginDirectory);

        assertFalse(inspection.valid());
        PaperLiveConfigGuard.Diagnostic error = inspection.diagnostics().stream()
            .filter(diagnostic -> diagnostic.severity() == PaperLiveConfigGuard.Severity.ERROR)
            .findFirst()
            .orElseThrow();
        assertEquals("broken", error.project());
        assertTrue(error.line() >= 2);
        assertFalse(PaperLiveConfigGuard.captureSuccessfulBaseline(pluginDirectory));
    }

    @Test
    void validatesRequiredPluginDescriptorFields() throws IOException {
        Path pluginDirectory = this.temporaryDirectory.resolve("plugins");
        Path resources = createProject(pluginDirectory, "missing-main");
        Files.writeString(resources.resolve("plugin.yml"), "name: MissingMain\nversion: 1.0\n");

        PaperLiveConfigGuard.Inspection inspection = PaperLiveConfigGuard.inspect(pluginDirectory);

        assertFalse(inspection.valid());
        assertTrue(inspection.diagnostics().stream().anyMatch(diagnostic -> diagnostic.file().getFileName().toString().equals("plugin.yml")));
    }

    @Test
    void validatesEveryInstalledPluginDataFolderAndTracksItsBaseline() throws IOException {
        Path pluginDirectory = this.temporaryDirectory.resolve("plugins");
        Path robbery = pluginDirectory.resolve("Robbery");
        Path economy = pluginDirectory.resolve("Economy").resolve("shops");
        Files.createDirectories(robbery);
        Files.createDirectories(economy);
        Files.writeString(robbery.resolve("config.yml"), "weapons:\n  enabled: true\n");
        Files.writeString(economy.resolve("prices.yml"), "iron: 25\n");

        PaperLiveConfigGuard.Inspection initial = PaperLiveConfigGuard.inspect(pluginDirectory);

        assertTrue(initial.valid());
        assertTrue(initial.changes().stream().anyMatch(change -> change.project().equals("Installed: Robbery") && change.relativePath().equals("config.yml")));
        assertTrue(initial.changes().stream().anyMatch(change -> change.project().equals("Installed: Economy") && change.relativePath().equals("shops/prices.yml")));
        assertTrue(PaperLiveConfigGuard.captureSuccessfulBaseline(pluginDirectory));

        Files.writeString(robbery.resolve("config.yml"), "weapons:\n  enabled: true\n broken: indentation\n");
        PaperLiveConfigGuard.Inspection invalid = PaperLiveConfigGuard.inspect(pluginDirectory);

        assertFalse(invalid.valid());
        assertTrue(invalid.diagnostics().stream().anyMatch(diagnostic -> diagnostic.project().equals("Installed: Robbery")));
        assertFalse(PaperLiveConfigGuard.captureSuccessfulBaseline(pluginDirectory));
    }

    private static Path createProject(Path pluginDirectory, String name) throws IOException {
        Path project = pluginDirectory.resolve("PaperLive").resolve("projects").resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }\n");
        Path resources = project.resolve("src").resolve("main").resolve("resources");
        Files.createDirectories(resources);
        return resources;
    }
}
