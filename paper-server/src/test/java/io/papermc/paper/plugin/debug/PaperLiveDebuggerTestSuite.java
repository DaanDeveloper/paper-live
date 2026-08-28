package io.papermc.paper.plugin.debug;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({PaperLiveDebuggerTest.class, PaperLivePluginDependenciesTest.class, PaperLiveScenarioRecorderTest.class})
public class PaperLiveDebuggerTestSuite {
}
