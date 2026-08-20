package io.papermc.paper.plugin.manager;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@Tag("Normal")
class PaperLiveRuntimeRegistryTest {

    @Test
    void tracksEventRegistrationsForTheExactPluginInstance() {
        PaperLiveRuntimeRegistry registry = new PaperLiveRuntimeRegistry();
        Plugin firstPlugin = mock(Plugin.class);
        Plugin secondPlugin = mock(Plugin.class);

        registry.recordEventRegistrations(firstPlugin, 2);
        registry.recordEventRegistrations(secondPlugin, 1);

        assertEquals(2, registry.snapshot(firstPlugin).eventRegistrationCount());
        assertEquals(1, registry.snapshot(secondPlugin).eventRegistrationCount());
    }

    @Test
    void releaseReturnsTheFinalSnapshotAndClearsThePluginOwnership() {
        PaperLiveRuntimeRegistry registry = new PaperLiveRuntimeRegistry();
        Plugin plugin = mock(Plugin.class);

        registry.recordEventRegistrations(plugin, 3);

        assertEquals(3, registry.release(plugin).eventRegistrationCount());
        assertEquals(0, registry.snapshot(plugin).eventRegistrationCount());
    }

    @Test
    void ignoresEmptyRegistrations() {
        PaperLiveRuntimeRegistry registry = new PaperLiveRuntimeRegistry();
        Plugin plugin = mock(Plugin.class);

        registry.recordEventRegistrations(plugin, 0);
        registry.recordEventRegistrations(plugin, -1);

        assertEquals(0, registry.snapshot(plugin).eventRegistrationCount());
    }
}
