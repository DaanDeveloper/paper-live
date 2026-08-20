package io.papermc.paper.plugin;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("Normal")
class PaperLiveCommandTest {

    @Test
    void completesRefreshFromTheFirstCharacter() {
        assertEquals(List.of("refresh"), PaperLiveCommand.completions("r"));
    }

    @Test
    void completesCommandsWithoutCaseSensitivity() {
        assertEquals(List.of("projects"), PaperLiveCommand.completions("P"));
    }

    @Test
    void returnsAllSubcommandsForAnEmptyArgument() {
        assertEquals(List.of("refresh", "projects", "help"), PaperLiveCommand.completions(""));
    }
}
