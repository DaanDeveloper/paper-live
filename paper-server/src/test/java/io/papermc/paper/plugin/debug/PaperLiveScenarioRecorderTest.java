package io.papermc.paper.plugin.debug;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperLiveScenarioRecorderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsReplayableCommandsObservationsAndExpectedState() throws IOException {
        PaperLiveScenarioRecorder.Scenario scenario = new PaperLiveScenarioRecorder.Scenario(
            "shop-purchase",
            "Daan",
            List.of(
                new PaperLiveScenarioRecorder.Step("COMMAND", "shop buy diamond", Map.of("owner", "Shop")),
                new PaperLiveScenarioRecorder.Step("OBSERVATION", "PlayerInteractEvent", Map.of("item", "DIAMOND"))
            ),
            new PaperLiveScenarioRecorder.PlayerState("dev", 0.5, 65, 0.5, Map.of()),
            new PaperLiveScenarioRecorder.PlayerState("dev", 1.5, 65, -2.5, Map.of("DIAMOND", 3))
        );
        Path file = this.temporaryDirectory.resolve("shop-purchase.yml");

        PaperLiveScenarioRecorder.save(file, scenario);
        PaperLiveScenarioRecorder.Scenario loaded = PaperLiveScenarioRecorder.load(file);

        assertEquals(scenario, loaded);
        assertEquals(List.of("shop-purchase"), PaperLiveScenarioRecorder.list(this.temporaryDirectory));
    }

    @Test
    void persistsReplayablePlayerInteractionsAndInventoryClicks() throws IOException {
        PaperLiveScenarioRecorder.Scenario scenario = new PaperLiveScenarioRecorder.Scenario(
            "robbery-craft-weapon",
            "Daan",
            List.of(
                new PaperLiveScenarioRecorder.Step("PLAYER_INTERACT", "PlayerInteractEvent", Map.of(
                    "action", "RIGHT_CLICK_BLOCK", "clickedBlock", "CRAFTING_TABLE", "clickedBlockX", "12", "clickedBlockY", "64", "clickedBlockZ", "-4"
                )),
                new PaperLiveScenarioRecorder.Step("INVENTORY_CLICK", "InventoryClickEvent", Map.of(
                    "rawSlot", "13", "click", "LEFT", "inventoryType", "CHEST", "item", "IRON_SWORD"
                ))
            ),
            new PaperLiveScenarioRecorder.PlayerState("dev", 12.5, 64, -3.5, Map.of()),
            new PaperLiveScenarioRecorder.PlayerState("dev", 12.5, 64, -3.5, Map.of("IRON_SWORD", 1))
        );
        Path file = this.temporaryDirectory.resolve("robbery-craft-weapon.yml");

        PaperLiveScenarioRecorder.save(file, scenario);

        assertEquals(scenario, PaperLiveScenarioRecorder.load(file));
    }

    @Test
    void mapsBukkitInventoryClicksToVanillaContainerInputs() {
        assertEquals(new PaperLiveScenarioRecorder.ClickInput(ContainerInput.PICKUP, (byte) 0), PaperLiveScenarioRecorder.clickInput(ClickType.LEFT, -1));
        assertEquals(new PaperLiveScenarioRecorder.ClickInput(ContainerInput.PICKUP, (byte) 1), PaperLiveScenarioRecorder.clickInput(ClickType.RIGHT, -1));
        assertEquals(new PaperLiveScenarioRecorder.ClickInput(ContainerInput.QUICK_MOVE, (byte) 0), PaperLiveScenarioRecorder.clickInput(ClickType.SHIFT_LEFT, -1));
        assertEquals(new PaperLiveScenarioRecorder.ClickInput(ContainerInput.SWAP, (byte) 4), PaperLiveScenarioRecorder.clickInput(ClickType.NUMBER_KEY, 4));
        assertEquals(new PaperLiveScenarioRecorder.ClickInput(ContainerInput.SWAP, (byte) 40), PaperLiveScenarioRecorder.clickInput(ClickType.SWAP_OFFHAND, -1));
        assertEquals(new PaperLiveScenarioRecorder.ClickInput(ContainerInput.PICKUP_ALL, (byte) 0), PaperLiveScenarioRecorder.clickInput(ClickType.DOUBLE_CLICK, -1));
    }

    @Test
    void persistsAndTogglesScenarioBreakpoints() throws IOException {
        PaperLiveScenarioRecorder.Scenario scenario = new PaperLiveScenarioRecorder.Scenario(
            "debug-robbery",
            "Daan",
            List.of(
                new PaperLiveScenarioRecorder.Step("COMMAND", "robbery", Map.of()),
                new PaperLiveScenarioRecorder.Step("INVENTORY_CLICK", "InventoryClickEvent", Map.of())
            ),
            new PaperLiveScenarioRecorder.PlayerState("dev", 0, 64, 0, Map.of()),
            new PaperLiveScenarioRecorder.PlayerState("dev", 0, 64, 0, Map.of())
        );
        Path file = this.temporaryDirectory.resolve("debug-robbery.yml");
        PaperLiveScenarioRecorder.save(file, scenario);

        PaperLiveScenarioRecorder.Scenario enabled = PaperLiveScenarioRecorder.setBreakpoint(file, 1, true);

        assertEquals(java.util.Set.of(1), enabled.breakpoints());
        assertEquals(java.util.Set.of(1), PaperLiveScenarioRecorder.load(file).breakpoints());
        assertTrue(PaperLiveScenarioRecorder.setBreakpoint(file, 1, false).breakpoints().isEmpty());
    }

    @Test
    void deletesOnlyAValidNamedScenario() throws IOException {
        PaperLiveScenarioRecorder.Scenario scenario = new PaperLiveScenarioRecorder.Scenario(
            "temporary-scenario",
            "Daan",
            List.of(),
            new PaperLiveScenarioRecorder.PlayerState("dev", 0, 64, 0, Map.of()),
            new PaperLiveScenarioRecorder.PlayerState("dev", 0, 64, 0, Map.of())
        );
        Path file = this.temporaryDirectory.resolve("temporary-scenario.yml");
        PaperLiveScenarioRecorder.save(file, scenario);

        assertTrue(PaperLiveScenarioRecorder.delete(this.temporaryDirectory, "temporary-scenario"));
        assertTrue(PaperLiveScenarioRecorder.list(this.temporaryDirectory).isEmpty());
        assertTrue(!PaperLiveScenarioRecorder.delete(this.temporaryDirectory, "temporary-scenario"));
    }
}
