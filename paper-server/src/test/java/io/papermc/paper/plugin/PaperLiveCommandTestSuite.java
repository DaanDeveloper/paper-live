package io.papermc.paper.plugin;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({PaperLiveCommandTest.class, PaperLiveScenarioCommandTest.class, PaperLiveWorldManagerTest.class, PaperLiveConfigGuardTest.class, PaperLiveSnapshotStoreTest.class, PaperLiveConflictBisectorTest.class})
public class PaperLiveCommandTestSuite {
}
