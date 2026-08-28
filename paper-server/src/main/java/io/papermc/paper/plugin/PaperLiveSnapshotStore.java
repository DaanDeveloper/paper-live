package io.papermc.paper.plugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jetbrains.annotations.NotNull;

/** Safe archive/restore primitive used by reproducible PaperLive development snapshots. */
final class PaperLiveSnapshotStore {

    private PaperLiveSnapshotStore() {
    }

    static void create(@NotNull Path snapshot, @NotNull List<Target> targets) throws IOException {
        if (targets.isEmpty()) {
            throw new IOException("A snapshot needs at least one target");
        }
        validateTargets(targets);
        if (Files.exists(snapshot)) {
            throw new IOException("Snapshot already exists: " + snapshot.getFileName());
        }
        Files.createDirectories(snapshot.toAbsolutePath().normalize().getParent());
        Path temporary = snapshot.resolveSibling(snapshot.getFileName() + ".tmp").toAbsolutePath().normalize();
        Files.deleteIfExists(temporary);
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            for (Target target : targets) {
                writeTarget(output, target);
            }
        } catch (IOException | RuntimeException | Error throwable) {
            Files.deleteIfExists(temporary);
            throw throwable;
        }
        try {
            Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temporary, snapshot);
        }
    }

    static RestoreResult restore(@NotNull Path snapshot, @NotNull List<Target> targets, @NotNull Path recoveryRoot) throws IOException {
        validateTargets(targets);
        if (!Files.isRegularFile(snapshot)) {
            throw new IOException("Snapshot does not exist: " + snapshot.getFileName());
        }
        Map<String, Target> targetsByLabel = new LinkedHashMap<>();
        for (Target target : targets) {
            targetsByLabel.put(target.label(), target);
        }
        validateArchive(snapshot, targetsByLabel.keySet());

        Path recovery = recoveryRoot.resolve("restore-" + Instant.now().toEpochMilli()).toAbsolutePath().normalize();
        Files.createDirectories(recovery);
        List<Target> moved = new ArrayList<>();
        try {
            for (Target target : targets) {
                if (Files.exists(target.path())) {
                    Path backup = recovery.resolve(target.label()).normalize();
                    ensureBelow(recovery, backup);
                    Files.createDirectories(backup.getParent());
                    Files.move(target.path(), backup);
                    moved.add(target);
                }
            }
            extract(snapshot, targetsByLabel);
            return new RestoreResult(recovery, List.copyOf(moved));
        } catch (IOException | RuntimeException | Error failure) {
            for (Target target : targets) {
                Path backup = recovery.resolve(target.label()).normalize();
                if (Files.exists(target.path())) {
                    deleteTree(target.path());
                }
                if (Files.exists(backup)) {
                    Files.move(backup, target.path());
                }
            }
            throw failure;
        }
    }

    private static void writeTarget(ZipOutputStream output, Target target) throws IOException {
        Path root = target.path().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Snapshot target is not a directory: " + root);
        }
        output.putNextEntry(new ZipEntry(target.label() + '/'));
        output.closeEntry();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.equals("session.lock")) {
                    continue;
                }
                String entryName = target.label() + '/' + relative + (Files.isDirectory(path) ? "/" : "");
                ZipEntry entry = new ZipEntry(entryName);
                output.putNextEntry(entry);
                if (Files.isRegularFile(path)) {
                    Files.copy(path, output);
                }
                output.closeEntry();
            }
        }
    }

    private static void validateArchive(Path snapshot, Set<String> expectedLabels) throws IOException {
        Set<String> actualLabels = new java.util.HashSet<>();
        try (ZipFile archive = new ZipFile(snapshot.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                int separator = name.indexOf('/');
                if (separator < 1 || !expectedLabels.contains(name.substring(0, separator))) {
                    throw new IOException("Snapshot contains an unexpected target: " + name);
                }
                actualLabels.add(name.substring(0, separator));
                if (name.startsWith("/") || name.contains("../") || name.contains("..\\")) {
                    throw new IOException("Unsafe snapshot entry: " + name);
                }
            }
        }
        if (!actualLabels.equals(expectedLabels)) {
            throw new IOException("Snapshot targets do not match the current dev environment");
        }
    }

    private static void extract(Path snapshot, Map<String, Target> targets) throws IOException {
        try (ZipFile archive = new ZipFile(snapshot.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                int separator = name.indexOf('/');
                Target target = targets.get(name.substring(0, separator));
                String relative = name.substring(separator + 1);
                if (relative.isEmpty()) {
                    Files.createDirectories(target.path());
                    continue;
                }
                Path destination = target.path().toAbsolutePath().normalize().resolve(relative).normalize();
                ensureBelow(target.path().toAbsolutePath().normalize(), destination);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry));
                         BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
                        input.transferTo(output);
                    }
                }
            }
        }
    }

    private static void validateTargets(List<Target> targets) throws IOException {
        Set<String> labels = new java.util.HashSet<>();
        for (Target target : targets) {
            if (!target.label().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || !labels.add(target.label())) {
                throw new IOException("Invalid or duplicate snapshot target label: " + target.label());
            }
            if (!target.path().isAbsolute()) {
                throw new IOException("Snapshot target must be absolute: " + target.path());
            }
        }
    }

    private static void ensureBelow(Path root, Path candidate) throws IOException {
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new IOException("Snapshot path escapes its target directory: " + candidate);
        }
    }

    private static void deleteTree(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IOException("Refusing to delete a filesystem root");
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    record Target(String label, Path path) {

        Target {
            path = path.toAbsolutePath().normalize();
        }
    }

    record RestoreResult(Path recoveryDirectory, List<Target> replacedTargets) {
    }
}
