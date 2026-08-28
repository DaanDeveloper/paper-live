package io.papermc.paper.plugin;

import org.bukkit.WorldType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Normal")
class PaperLiveWorldManagerTest {

    @Test
    void parsesFastFlatAndVoidWorldPresets() {
        PaperLiveWorldManager.WorldOptions flat = PaperLiveWorldManager.WorldOptions.parse(new String[]{"world", "create", "dev", "flat", "seed=42", "structures=false"}, 3);
        PaperLiveWorldManager.WorldOptions voidWorld = PaperLiveWorldManager.WorldOptions.parse(new String[]{"world", "reset", "arena", "void", "platform=false", "confirm"}, 3);

        assertEquals(WorldType.FLAT, flat.worldType());
        assertEquals(42L, flat.seed());
        assertFalse(flat.structures());
        assertEquals("void", voidWorld.preset());
        assertFalse(voidWorld.platform());
        assertTrue(voidWorld.confirmed());
    }
}
