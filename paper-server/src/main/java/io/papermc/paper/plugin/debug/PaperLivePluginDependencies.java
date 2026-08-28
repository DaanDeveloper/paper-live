package io.papermc.paper.plugin.debug;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.configuration.PaperPluginMeta;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jspecify.annotations.NullMarked;

/** Builds display-ready direct dependency relationships for the PaperLive plugin manager. */
@NullMarked
final class PaperLivePluginDependencies {

    private PaperLivePluginDependencies() {
    }

    static Map<String, Relationship> resolve(Collection<Descriptor> descriptors) {
        Map<String, Descriptor> descriptorsByName = new LinkedHashMap<>();
        Map<String, String> canonicalNames = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptors) {
            descriptorsByName.put(normalize(descriptor.name()), descriptor);
            canonicalNames.put(normalize(descriptor.name()), descriptor.name());
            for (String provided : descriptor.provides()) {
                canonicalNames.putIfAbsent(normalize(provided), descriptor.name());
            }
        }

        Map<String, Set<String>> requiredBy = new LinkedHashMap<>();
        Map<String, Set<String>> optionallyUsedBy = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptors) {
            addReverseRelationships(descriptor.name(), descriptor.required(), canonicalNames, requiredBy);
            addReverseRelationships(descriptor.name(), descriptor.optional(), canonicalNames, optionallyUsedBy);
        }

        Map<String, Relationship> relationships = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptorsByName.values()) {
            relationships.put(normalize(descriptor.name()), new Relationship(
                displayDependencies(descriptor.required(), canonicalNames),
                displayDependencies(descriptor.optional(), canonicalNames),
                sorted(requiredBy.getOrDefault(normalize(descriptor.name()), Set.of())),
                sorted(optionallyUsedBy.getOrDefault(normalize(descriptor.name()), Set.of()))
            ));
        }
        return Map.copyOf(relationships);
    }

    static PluginJar readPluginJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            JarEntry paperDescriptor = jar.getJarEntry("paper-plugin.yml");
            if (paperDescriptor != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(paperDescriptor), StandardCharsets.UTF_8))) {
                    return pluginJar(path, PaperPluginMeta.create(reader));
                } catch (Exception exception) {
                    throw new IOException("Invalid paper-plugin.yml in " + path.getFileName(), exception);
                }
            }

            JarEntry bukkitDescriptor = jar.getJarEntry("plugin.yml");
            if (bukkitDescriptor == null) {
                throw new IOException("No plugin descriptor in " + path.getFileName());
            }
            try {
                return pluginJar(path, new PluginDescriptionFile(jar.getInputStream(bukkitDescriptor)));
            } catch (Exception exception) {
                throw new IOException("Invalid plugin.yml in " + path.getFileName(), exception);
            }
        }
    }

    static String dependsOnDisplay(Relationship relationship) {
        List<String> values = new ArrayList<>(relationship.required());
        relationship.optional().forEach(dependency -> values.add(dependency + " (optional)"));
        return values.isEmpty() ? "—" : String.join(", ", values);
    }

    static String dependentsDisplay(Relationship relationship) {
        List<String> values = new ArrayList<>(relationship.requiredBy());
        relationship.optionallyUsedBy().forEach(dependent -> values.add(dependent + " (optional)"));
        return values.isEmpty() ? "—" : String.join(", ", values);
    }

    private static void addReverseRelationships(
        String dependent,
        List<String> dependencies,
        Map<String, String> canonicalNames,
        Map<String, Set<String>> reverseRelationships
    ) {
        for (String dependency : dependencies) {
            String canonicalName = canonicalNames.getOrDefault(normalize(dependency), dependency);
            reverseRelationships.computeIfAbsent(normalize(canonicalName), ignored -> new LinkedHashSet<>()).add(dependent);
        }
    }

    private static List<String> displayDependencies(List<String> dependencies, Map<String, String> canonicalNames) {
        return dependencies.stream()
            .map(dependency -> canonicalNames.getOrDefault(normalize(dependency), dependency))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static PluginJar pluginJar(Path path, PluginMeta meta) {
        return new PluginJar(
            path,
            meta.getName(),
            meta.getVersion(),
            new Descriptor(meta.getName(), meta.getPluginDependencies(), meta.getPluginSoftDependencies(), meta.getProvidedPlugins())
        );
    }

    record Descriptor(String name, List<String> required, List<String> optional, List<String> provides) {

        Descriptor {
            required = List.copyOf(required);
            optional = List.copyOf(optional);
            provides = List.copyOf(provides);
        }
    }

    record Relationship(List<String> required, List<String> optional, List<String> requiredBy, List<String> optionallyUsedBy) {
    }

    record PluginJar(Path path, String name, String version, Descriptor descriptor) {
    }
}
