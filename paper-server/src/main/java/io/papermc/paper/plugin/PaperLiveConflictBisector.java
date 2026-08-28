package io.papermc.paper.plugin;

import io.papermc.paper.plugin.debug.PaperLiveScenarioRecorder;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Dependency-aware binary isolation of one conflicting PaperLive source plugin. */
public final class PaperLiveConflictBisector {

    private PaperLiveConflictBisector() {
    }

    public static @NotNull Result run(
        @NotNull Consumer<String> output,
        @NotNull String snapshotName,
        @NotNull String worldName,
        @NotNull Path scenarioFile,
        @NotNull Player player
    ) throws IOException {
        List<String> candidates = new ArrayList<>(PaperLiveDevSnapshots.sourcePluginNames());
        if (candidates.isEmpty()) {
            return new Result(false, null, 0, "No PaperLive source plugins are available to bisect");
        }
        int iterations = 0;
        try {
            PaperLiveDevSnapshots.restore(output, snapshotName, worldName);
            PaperLiveScenarioRecorder.ReplayResult baseline = PaperLiveScenarioRecorder.instance().replay(scenarioFile, player);
            if (baseline.successful()) {
                return new Result(false, null, 0, "The scenario passes with every source plugin loaded; there is no reproducible conflict");
            }

            while (candidates.size() > 1) {
                iterations++;
                PaperLiveDevSnapshots.restore(output, snapshotName, worldName);
                List<String> requestedDisabled = candidates.subList(0, Math.max(1, candidates.size() / 2));
                Set<String> actuallyDisabled = unload(requestedDisabled);
                PaperLiveScenarioRecorder.ReplayResult replay = PaperLiveScenarioRecorder.instance().replay(scenarioFile, player);
                output.accept("§7[PaperLive] Bisect " + iterations + ": disabled " + String.join(", ", actuallyDisabled)
                    + " → scenario " + (replay.successful() ? "passed" : "still failed"));
                candidates = nextCandidates(candidates, actuallyDisabled, replay.successful());
                if (candidates.isEmpty()) {
                    return new Result(false, null, iterations, "Dependency expansion made the result ambiguous");
                }
            }

            String suspect = candidates.getFirst();
            PaperLiveDevSnapshots.restore(output, snapshotName, worldName);
            unload(List.of(suspect));
            boolean confirmed = PaperLiveScenarioRecorder.instance().replay(scenarioFile, player).successful();
            return confirmed
                ? new Result(true, suspect, iterations + 1, "Scenario passes when '" + suspect + "' and its dependents are disabled")
                : new Result(false, suspect, iterations + 1, "'" + suspect + "' is the remaining suspect, but the final verification still failed");
        } finally {
            try {
                PaperLiveDevSnapshots.restore(output, snapshotName, worldName);
            } catch (Throwable restoreFailure) {
                output.accept("§c[PaperLive] Could not restore the all-plugins-loaded baseline after bisect: " + restoreFailure.getMessage());
            }
        }
    }

    static List<String> nextCandidates(Collection<String> current, Collection<String> actuallyDisabled, boolean scenarioPassed) {
        Set<String> disabled = actuallyDisabled.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return current.stream()
            .filter(candidate -> scenarioPassed == disabled.contains(candidate.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private static Set<String> unload(Collection<String> pluginNames) {
        Set<String> disabled = new LinkedHashSet<>();
        PaperPluginManagerImpl manager = PaperPluginManagerImpl.getInstance();
        for (String pluginName : pluginNames) {
            PaperPluginManagerImpl.PluginUnloadResult result = manager.unloadPlugin(pluginName, true);
            if (!result.found()) {
                continue;
            }
            if (!result.successful()) {
                throw new IllegalArgumentException("Could not disable '" + pluginName + "': " + String.join(", ", result.blockers()));
            }
            disabled.add(pluginName);
            disabled.addAll(result.dependents());
        }
        return disabled;
    }

    public record Result(boolean confirmed, String suspect, int iterations, String explanation) {
    }
}
