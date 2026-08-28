package io.papermc.paper.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperLiveSnapshotStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresMultipleTargetsAndKeepsRecoveryBackup() throws IOException {
        Path world = Files.createDirectories(this.temporaryDirectory.resolve("world")).toAbsolutePath();
        Path pluginData = Files.createDirectories(this.temporaryDirectory.resolve("plugin-data")).toAbsolutePath();
        Files.writeString(world.resolve("level.dat"), "before");
        Files.writeString(pluginData.resolve("config.yml"), "enabled: true\n");
        Path snapshot = this.temporaryDirectory.resolve("snapshots/dev.zip");
        List<PaperLiveSnapshotStore.Target> targets = List.of(
            new PaperLiveSnapshotStore.Target("world", world),
            new PaperLiveSnapshotStore.Target("plugin-Shop", pluginData)
        );
        PaperLiveSnapshotStore.create(snapshot, targets);

        Files.writeString(world.resolve("level.dat"), "after");
        Files.writeString(pluginData.resolve("config.yml"), "enabled: false\n");
        PaperLiveSnapshotStore.RestoreResult result = PaperLiveSnapshotStore.restore(
            snapshot,
            targets,
            this.temporaryDirectory.resolve("recovery").toAbsolutePath()
        );

        assertEquals("before", Files.readString(world.resolve("level.dat")));
        assertEquals("enabled: true\n", Files.readString(pluginData.resolve("config.yml")));
        assertTrue(Files.exists(result.recoveryDirectory().resolve("world/level.dat")));
        assertEquals("after", Files.readString(result.recoveryDirectory().resolve("world/level.dat")));
    }

    @Test
    void rejectsZipSlipEntriesBeforeMovingTargets() throws IOException {
        Path world = Files.createDirectories(this.temporaryDirectory.resolve("world")).toAbsolutePath();
        Files.writeString(world.resolve("level.dat"), "safe");
        Path snapshot = this.temporaryDirectory.resolve("evil.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(snapshot))) {
            output.putNextEntry(new ZipEntry("world/../outside.txt"));
            output.write("bad".getBytes());
            output.closeEntry();
        }

        assertThrows(IOException.class, () -> PaperLiveSnapshotStore.restore(
            snapshot,
            List.of(new PaperLiveSnapshotStore.Target("world", world)),
            this.temporaryDirectory.resolve("recovery").toAbsolutePath()
        ));
        assertEquals("safe", Files.readString(world.resolve("level.dat")));
    }
}
