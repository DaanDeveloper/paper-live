package io.papermc.paper.plugin;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Normal")
class PaperLiveSourceWatcherTest {

    private static final Path PROJECTS = Path.of("plugins", "PaperLive", "projects");

    @Test
    void recognizesJavaSourceChanges() {
        Path sourceFile = PROJECTS.resolve("robberyplugin/src/main/java/nl/robbery/RobberyPlugin.java");

        assertTrue(PaperLiveSourceWatcher.isRelevantPath(PROJECTS, sourceFile, false));
    }

    @Test
    void recognizesNewProjectDirectories() {
        Path projectDirectory = PROJECTS.resolve("robberyplugin");

        assertTrue(PaperLiveSourceWatcher.isRelevantPath(PROJECTS, projectDirectory, true));
    }

    @Test
    void ignoresGradleBuildOutput() {
        Path classFile = PROJECTS.resolve("robberyplugin/build/classes/java/main/RobberyPlugin.class");

        assertFalse(PaperLiveSourceWatcher.isRelevantPath(PROJECTS, classFile, false));
    }

    @Test
    void ignoresMavenBuildOutput() {
        Path classFile = PROJECTS.resolve("robberyplugin/target/classes/RobberyPlugin.class");

        assertFalse(PaperLiveSourceWatcher.isRelevantPath(PROJECTS, classFile, false));
    }
}
