package io.papermc.paper.plugin.debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperLivePluginDependenciesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesDirectAndReverseRelationships() {
        Map<String, PaperLivePluginDependencies.Relationship> relationships = PaperLivePluginDependencies.resolve(List.of(
            descriptor("Vault", List.of(), List.of(), List.of()),
            descriptor("Economy", List.of("vault"), List.of(), List.of()),
            descriptor("Shop", List.of("Economy"), List.of("PlaceholderAPI"), List.of()),
            descriptor("PlaceholderAPI", List.of(), List.of(), List.of())
        ));

        assertEquals("Economy, PlaceholderAPI (optional)", PaperLivePluginDependencies.dependsOnDisplay(relationships.get("shop")));
        assertEquals("Economy", PaperLivePluginDependencies.dependentsDisplay(relationships.get("vault")));
        assertEquals("Shop (optional)", PaperLivePluginDependencies.dependentsDisplay(relationships.get("placeholderapi")));
    }

    @Test
    void resolvesProvidedDependencyAliasesToCanonicalPlugin() {
        Map<String, PaperLivePluginDependencies.Relationship> relationships = PaperLivePluginDependencies.resolve(List.of(
            descriptor("EconomyBridge", List.of(), List.of(), List.of("Vault")),
            descriptor("Shop", List.of("vault"), List.of(), List.of())
        ));

        assertEquals("EconomyBridge", PaperLivePluginDependencies.dependsOnDisplay(relationships.get("shop")));
        assertEquals("Shop", PaperLivePluginDependencies.dependentsDisplay(relationships.get("economybridge")));
    }

    @Test
    void displaysNoRelationshipsAsDash() {
        PaperLivePluginDependencies.Relationship relationship = PaperLivePluginDependencies.resolve(List.of(
            descriptor("Standalone", List.of(), List.of(), List.of())
        )).get("standalone");

        assertEquals("—", PaperLivePluginDependencies.dependsOnDisplay(relationship));
        assertEquals("—", PaperLivePluginDependencies.dependentsDisplay(relationship));
    }

    @Test
    void readsDependenciesFromUnloadedBukkitJar() throws IOException {
        Path pluginJar = this.temporaryDirectory.resolve("shop.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(pluginJar))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: Shop\nversion: 1.2.3\nmain: example.Shop\ndepend: [Vault]\nsoftdepend: [PlaceholderAPI]\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        PaperLivePluginDependencies.PluginJar metadata = PaperLivePluginDependencies.readPluginJar(pluginJar);

        assertEquals("Shop", metadata.name());
        assertEquals("1.2.3", metadata.version());
        assertEquals(List.of("Vault"), metadata.descriptor().required());
        assertEquals(List.of("PlaceholderAPI"), metadata.descriptor().optional());
    }

    private static PaperLivePluginDependencies.Descriptor descriptor(String name, List<String> required, List<String> optional, List<String> provides) {
        return new PaperLivePluginDependencies.Descriptor(name, required, optional, provides);
    }
}
