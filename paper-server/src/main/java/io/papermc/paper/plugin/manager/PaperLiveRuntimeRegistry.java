package io.papermc.paper.plugin.manager;

import java.util.IdentityHashMap;
import java.util.Map;
import org.bukkit.plugin.Plugin;
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

        return new RuntimeOwnershipSnapshot(ownership.eventRegistrationCount);
    }

    /**
     * Removes and returns the tracked ownership once Paper has released the plugin resources.
     *
     * @param plugin the plugin whose lifecycle has ended
     * @return the final ownership snapshot
     */
    synchronized @NotNull RuntimeOwnershipSnapshot release(@NotNull Plugin plugin) {
        RuntimeOwnership ownership = this.ownershipByPlugin.remove(plugin);

        if (ownership == null) {
            return RuntimeOwnershipSnapshot.EMPTY;
        }

        return new RuntimeOwnershipSnapshot(ownership.eventRegistrationCount);
    }

    private static final class RuntimeOwnership {

        private int eventRegistrationCount;
    }

    /**
     * Immutable ownership information captured for diagnostics and lifecycle decisions.
     *
     * @param eventRegistrationCount the number of event registrations made through Paper
     */
    record RuntimeOwnershipSnapshot(int eventRegistrationCount) {

        static final RuntimeOwnershipSnapshot EMPTY = new RuntimeOwnershipSnapshot(0);
    }
}
