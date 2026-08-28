package io.papermc.paper.plugin;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("Normal")
class PaperLiveScenarioCommandTest {

    @Test
    void completesScenarioActionsWithoutCaseSensitivity() {
        assertEquals(List.of("create", "cancel"), PaperLiveScenarioCommand.completions("C"));
    }

    @Test
    void returnsAllScenarioActionsForAnEmptyArgument() {
        assertEquals(List.of("create", "record", "stop", "cancel", "replay", "delete", "list", "status", "help"), PaperLiveScenarioCommand.completions(""));
    }
}
