package io.papermc.paper.plugin;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperLiveConflictBisectorTest {

    @Test
    void keepsDisabledHalfWhenScenarioPasses() {
        assertEquals(
            List.of("Economy", "Shop"),
            PaperLiveConflictBisector.nextCandidates(
                List.of("Economy", "Chat", "Shop", "Quests"),
                List.of("shop", "ECONOMY"),
                true
            )
        );
    }

    @Test
    void keepsEnabledHalfWhenScenarioStillFails() {
        assertEquals(
            List.of("Chat", "Quests"),
            PaperLiveConflictBisector.nextCandidates(
                List.of("Economy", "Chat", "Shop", "Quests"),
                List.of("Shop", "Economy"),
                false
            )
        );
    }
}
