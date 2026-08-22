package io.papermc.paper.plugin.manager;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks resources that PaperLive registers through Paper's plugin APIs.
 *
 * <p>The registry uses plugin identity rather than {@link Object#equals(Object)}. A plugin
 * implementation may define equality itself, but its runtime resources must always belong to the
 * exact loaded plugin instance.</p>
 */
final class PaperLiveRuntimeRegistry {

    private final Map<Plugin, RuntimeOwnership> ownershipByPlugin = new IdentityHashMap<>();

    /**
     * Records listeners registered through {@link org.bukkit.plugin.PluginManager}.
     *
     * @param plugin the loaded plugin that owns the registrations
     * @param registrationCount the number of event registrations created for the listener
     */
    synchronized void recordEventRegistrations(@NotNull Plugin plugin, int registrationCount) {
        if (registrationCount <= 0) {
            return;
        }

        RuntimeOwnership ownership = this.ownershipByPlugin.computeIfAbsent(plugin, ignored -> new RuntimeOwnership());
        ownership.eventRegistrationCount += registrationCount;
    }

    synchronized void recordBossBar(@NotNull Plugin plugin, @NotNull BossBar bossBar) {
        this.ownershipByPlugin.computeIfAbsent(plugin, ignored -> new RuntimeOwnership()).bossBars.add(bossBar);
    }

    synchronized void recordAdventureBossBar(@NotNull Plugin plugin, @NotNull net.kyori.adventure.bossbar.BossBar bossBar) {
        this.ownershipByPlugin.computeIfAbsent(plugin, ignored -> new RuntimeOwnership()).adventureBossBars.add(bossBar);
    }

    synchronized void recordScoreboard(@NotNull Plugin plugin, @NotNull Scoreboard scoreboard) {
        this.ownershipByPlugin.computeIfAbsent(plugin, ignored -> new RuntimeOwnership()).scoreboards.add(scoreboard);
    }

    /**
     * Returns the currently known ownership for one loaded plugin instance.
     *
     * @param plugin the plugin to inspect
     * @return a stable snapshot of the tracked resources
     */
    synchronized @NotNull RuntimeOwnershipSnapshot snapshot(@NotNull Plugin plugin) {
        RuntimeOwnership ownership = this.ownershipByPlugin.get(plugin);

        if (ownership == null) {
            return RuntimeOwnershipSnapshot.EMPTY;
        }

        return ownership.snapshot();
    }

    /**
     * Removes and returns the tracked ownership once Paper has released the plugin resources.
     *
     * @param plugin the plugin whose lifecycle has ended
     * @return the final ownership snapshot
     */
    @NotNull RuntimeOwnershipSnapshot release(@NotNull Plugin plugin) {
        RuntimeOwnership ownership;
        synchronized (this) {
            ownership = this.ownershipByPlugin.remove(plugin);
        }

        if (ownership == null) {
            return RuntimeOwnershipSnapshot.EMPTY;
        }

        ownership.releaseResources();
        return ownership.snapshot();
    }

    private static final class RuntimeOwnership {

        private int eventRegistrationCount;
        private final Set<BossBar> bossBars = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<net.kyori.adventure.bossbar.BossBar> adventureBossBars = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Scoreboard> scoreboards = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        private void releaseResources() {
            for (BossBar bossBar : this.bossBars) {
                bossBar.removeAll();
                bossBar.setVisible(false);
                if (bossBar instanceof KeyedBossBar keyedBossBar) {
                    Bukkit.removeBossBar(keyedBossBar.getKey());
                }
            }

            for (net.kyori.adventure.bossbar.BossBar bossBar : this.adventureBossBars) {
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    player.hideBossBar(bossBar);
                }
            }

            Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Scoreboard scoreboard : this.scoreboards) {
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getScoreboard() == scoreboard) {
                        player.setScoreboard(mainScoreboard);
                    }
                }
                for (Objective objective : java.util.List.copyOf(scoreboard.getObjectives())) {
                    objective.unregister();
                }
                for (Team team : java.util.List.copyOf(scoreboard.getTeams())) {
                    team.unregister();
                }
            }
        }

        private @NotNull RuntimeOwnershipSnapshot snapshot() {
            return new RuntimeOwnershipSnapshot(this.eventRegistrationCount, this.bossBars.size(), this.adventureBossBars.size(), this.scoreboards.size());
        }
    }

    /**
     * Immutable ownership information captured for diagnostics and lifecycle decisions.
     *
     * @param eventRegistrationCount the number of event registrations made through Paper
     */
    record RuntimeOwnershipSnapshot(int eventRegistrationCount, int bossBarCount, int adventureBossBarCount, int scoreboardCount) {

        static final RuntimeOwnershipSnapshot EMPTY = new RuntimeOwnershipSnapshot(0, 0, 0, 0);
    }
}
