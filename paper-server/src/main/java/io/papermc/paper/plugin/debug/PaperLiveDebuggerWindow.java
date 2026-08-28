package io.papermc.paper.plugin.debug;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import io.papermc.paper.plugin.PaperLiveWorldManager;
import io.papermc.paper.plugin.PaperLivePluginActions;
import io.papermc.paper.plugin.PaperLiveConfigGuard;
import io.papermc.paper.plugin.PaperLiveDevSnapshots;
import io.papermc.paper.plugin.PaperLiveConflictBisector;
import io.papermc.paper.plugin.PluginInitializerManager;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** The in-process PaperLive development tool window bundled with the server JAR. */
@NullMarked
public final class PaperLiveDebuggerWindow {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final Font MONOSPACED = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final int MAX_WINDOW_RECORDS = 1_000;

    private final Server server;
    private final PaperLiveDebugger debugger;
    private final JFrame frame = new JFrame("PaperLive Dev Tool");
    private final DefaultListModel<PaperLiveDebugRecord> visibleRecords = new DefaultListModel<>();
    private final JList<PaperLiveDebugRecord> recordList = new JList<>(this.visibleRecords);
    private final List<PaperLiveDebugRecord> allRecords = new ArrayList<>();
    private final Set<Long> knownSequences = new HashSet<>();
    private final Deque<PaperLiveDebugRecord> pendingRecords = new ArrayDeque<>();
    private final JTextField filterField = new JTextField();
    private final JCheckBox traceEvents = new JCheckBox("Trace events");
    private final DefaultComboBoxModel<String> traceEventModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> traceEventSelector = new JComboBox<>(this.traceEventModel);
    private final Set<String> knownEventChoices = new HashSet<>();
    private final DefaultComboBoxModel<String> tracePluginModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> tracePluginSelector = new JComboBox<>(this.tracePluginModel);
    private final Set<String> knownPluginChoices = new HashSet<>();
    private final DefaultComboBoxModel<String> tracePlayerModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> tracePlayerSelector = new JComboBox<>(this.tracePlayerModel);
    private final Set<String> knownPlayerChoices = new HashSet<>();
    private final JToggleButton pauseButton = new JToggleButton("Pause");
    private final JCheckBox alwaysOnTop = new JCheckBox("Always on top");
    private final JLabel statusLabel = new JLabel();
    private final JTextArea overview = textArea();
    private final JTextArea stackTrace = textArea();
    private final JTextField setupWorldName = new JTextField("dev");
    private final JComboBox<String> setupPreset = new JComboBox<>(new String[]{"flat", "normal", "void", "amplified", "large_biomes"});
    private final JTextField setupSeed = new JTextField("random");
    private final JComboBox<String> setupEnvironment = new JComboBox<>(new String[]{"normal", "nether", "the_end"});
    private final JCheckBox setupStructures = new JCheckBox("Generate structures", true);
    private final JCheckBox setupHardcore = new JCheckBox("Hardcore");
    private final JCheckBox setupBonusChest = new JCheckBox("Bonus chest");
    private final JCheckBox setupPlatform = new JCheckBox("Void spawn platform", true);
    private final JComboBox<String> setupPlayer = new JComboBox<>();
    private final JTextArea setupGeneratorSettings = new JTextArea(4, 32);
    private final JTextArea setupOutput = textArea();
    private final JButton setupCreateButton = new JButton("Create world");
    private final JButton setupResetButton = new JButton("Reset selected world");
    private final JButton setupTeleportButton = new JButton("Teleport player to world spawn");
    private final JTextField setupSnapshotName = new JTextField("baseline");
    private final JButton setupSnapshotCreateButton = new JButton("Save dev snapshot");
    private final JButton setupSnapshotRestoreButton = new JButton("Restore dev snapshot");
    private final DefaultTableModel setupWorldModel = new DefaultTableModel(
        new String[]{"World", "Preset", "Environment", "Seed", "Players", "Primary"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable setupWorldTable = new JTable(this.setupWorldModel);
    private final DefaultTableModel handlerModel = new DefaultTableModel(
        new String[]{"#", "Event", "Plugin", "Version", "Priority", "Listener", "Enabled", "Ignore cancelled", "Duration", "Cancelled", "Status"},
        0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable handlerTable = new JTable(this.handlerModel);
    private final DefaultTableModel pluginModel = new DefaultTableModel(
        new String[]{"Plugin / project", "Type", "Status", "Version", "Depends on", "Dependents", "Location"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable pluginTable = new JTable(this.pluginModel);
    private final JTextField pluginFilter = new JTextField();
    private final JLabel pluginStatus = new JLabel();
    private final AtomicBoolean pluginRefreshPending = new AtomicBoolean();
    private final Map<java.nio.file.Path, CachedPluginJar> pluginJarCache = new java.util.HashMap<>();
    private final JButton pluginLoadButton = new JButton("Load");
    private final JButton pluginUnloadButton = new JButton("Unload");
    private final JButton pluginEnableButton = new JButton("Enable");
    private final JButton pluginDisableButton = new JButton("Disable");
    private final DefaultTableModel configGuardModel = new DefaultTableModel(
        new String[]{"Plugin / project", "Configuration", "Validation", "Since last good", "Line", "Message"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable configGuardTable = new JTable(this.configGuardModel);
    private final JLabel configGuardStatus = new JLabel();
    private final AtomicBoolean configGuardRefreshPending = new AtomicBoolean();
    private final DefaultTableModel taskModel = new DefaultTableModel(
        new String[]{"Time", "Plugin", "Scheduler", "ID", "Task", "Duration", "Thread", "Repeating", "Status", "Registered at"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable taskTable = new JTable(this.taskModel);
    private final JTextField taskFilter = new JTextField();
    private final JLabel taskStatus = new JLabel();
    private final JComboBox<String> scenarioPlayer = new JComboBox<>();
    private final JTextField scenarioName = new JTextField("smoke-test");
    private final DefaultTableModel scenarioModel = new DefaultTableModel(
        new String[]{"Scenario", "Recorded player", "Commands", "Manual observations", "Expected world", "Expected inventory"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable scenarioTable = new JTable(this.scenarioModel);
    private final DefaultTableModel scenarioStepModel = new DefaultTableModel(
        new String[]{"Break", "#", "Kind", "Action / event", "Recorded details", "Replay"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable scenarioStepTable = new JTable(this.scenarioStepModel);
    private final JButton scenarioContinueButton = new JButton("Continue");
    private final JButton scenarioStopButton = new JButton("Stop replay");
    private PaperLiveScenarioRecorder.@Nullable ReplaySession activeScenarioReplay;
    private PaperLiveScenarioRecorder.@Nullable ReplayResult lastScenarioReplay;
    private final JLabel scenarioStatus = new JLabel("Ready");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean setupRefreshPending = new AtomicBoolean();
    private final PaperLiveDebugger.Subscription subscription;
    private final Timer handlerRefreshTimer;
    private final Timer recordRefreshTimer;
    private int pausedRecordCount;
    private boolean updatingSelectorModels;

    private PaperLiveDebuggerWindow(Server server, PaperLiveDebugger debugger) {
        this.server = server;
        this.debugger = debugger;
        PaperLiveDebugger.EventTraceFilter eventTraceFilter = debugger.eventTraceFilter();
        this.traceEvents.setSelected(eventTraceFilter.enabled());
        this.traceEventSelector.setEditable(true);
        this.addEventChoice(eventTraceFilter.eventInput());
        this.traceEventSelector.getEditor().setItem(eventTraceFilter.eventInput());
        this.tracePluginSelector.setEditable(true);
        this.addPluginChoice(eventTraceFilter.pluginInput());
        this.tracePluginSelector.getEditor().setItem(eventTraceFilter.pluginInput());
        this.tracePlayerSelector.setEditable(true);
        this.addPlayerChoice(eventTraceFilter.playerInput());
        this.tracePlayerSelector.getEditor().setItem(eventTraceFilter.playerInput());
        this.recordRefreshTimer = new Timer(100, event -> this.drainPendingRecords());
        this.recordRefreshTimer.setCoalesce(true);
        this.subscription = debugger.subscribe(this::receiveException);
        this.handlerRefreshTimer = new Timer(1000, event -> {
            this.refreshRegisteredHandlers();
            this.refreshCommandTimeline();
            this.refreshSetupWorlds();
            this.refreshPlugins();
            this.refreshTasks();
        });
        this.buildWindow();
        for (PaperLiveDebugRecord record : debugger.recentRecords()) {
            this.addRecord(record);
        }
        this.refreshStatus();
        this.refreshSetupWorlds();
        this.refreshPlugins();
        this.recordRefreshTimer.start();
        this.handlerRefreshTimer.start();
        this.frame.setVisible(true);
    }

    /** Opens the debugger when a desktop is available and the feature was not disabled. */
    public static @Nullable PaperLiveDebuggerWindow open(Server server, PaperLiveDebugger debugger) {
        if (!Boolean.parseBoolean(System.getProperty("paperlive.debugger.enabled", "true")) || GraphicsEnvironment.isHeadless()) {
            return null;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        AtomicReference<PaperLiveDebuggerWindow> openedWindow = new AtomicReference<>();
        Runnable createWindow = () -> openedWindow.set(new PaperLiveDebuggerWindow(server, debugger));
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                createWindow.run();
            } else {
                SwingUtilities.invokeAndWait(createWindow);
            }
        } catch (Throwable throwable) {
            server.getLogger().warning("[PaperLive] Could not open PaperLive Dev Tool: " + throwable.getMessage());
            return null;
        }

        PaperLiveDebuggerWindow window = openedWindow.get();
        window.startLifecycleWatcher();
        return window;
    }

    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.subscription.close();
        SwingUtilities.invokeLater(() -> {
            this.recordRefreshTimer.stop();
            this.handlerRefreshTimer.stop();
            this.frame.dispose();
        });
    }

    private void buildWindow() {
        this.frame.setName("PaperLive Dev Tool");
        this.frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        this.frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                PaperLiveDebuggerWindow.this.frame.setState(Frame.ICONIFIED);
            }
        });
        this.frame.setIconImage(loadIcon());
        this.frame.setMinimumSize(new Dimension(900, 560));
        this.frame.setSize(1200, 760);
        this.frame.setLocationByPlatform(true);

        this.recordList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.recordList.setCellRenderer(new RecordRenderer());
        this.recordList.setFixedCellHeight(26);
        this.recordList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                this.showDetails(this.recordList.getSelectedValue());
            }
        });

        JScrollPane listScroll = new JScrollPane(this.recordList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Live diagnostics"));
        listScroll.setPreferredSize(new Dimension(390, 600));

        this.handlerTable.setAutoCreateRowSorter(true);
        this.handlerTable.setFillsViewportHeight(true);
        this.handlerTable.getColumnModel().getColumn(0).setMaxWidth(45);
        this.handlerTable.getColumnModel().getColumn(6).setMaxWidth(70);
        this.handlerTable.getColumnModel().getColumn(7).setMaxWidth(110);
        this.handlerTable.getColumnModel().getColumn(8).setMaxWidth(90);
        this.handlerTable.getColumnModel().getColumn(9).setMaxWidth(90);
        this.handlerTable.getColumnModel().getColumn(10).setMaxWidth(130);

        JTabbedPane details = new JTabbedPane();
        details.addTab("Overview", new JScrollPane(this.overview));
        details.addTab("Handler order", new JScrollPane(this.handlerTable));
        details.addTab("Output / stack trace", new JScrollPane(this.stackTrace));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, details);
        splitPane.setResizeWeight(0.33);
        splitPane.setDividerLocation(390);

        JPanel header = new JPanel(new BorderLayout(12, 4));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        JLabel title = new JLabel("Debugger");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20F));
        JLabel subtitle = new JLabel("Live event exceptions and plugin handler context");
        subtitle.setForeground(new Color(90, 90, 90));
        JPanel titles = new JPanel(new BorderLayout());
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        header.add(titles, BorderLayout.WEST);
        header.add(this.statusLabel, BorderLayout.EAST);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        toolbar.add(new JLabel("Filter: "));
        this.filterField.setMaximumSize(new Dimension(360, 28));
        this.filterField.setPreferredSize(new Dimension(280, 28));
        this.filterField.setToolTipText("Filter by event, plugin, player, world, item, or exception text");
        toolbar.add(this.filterField);
        toolbar.addSeparator();
        toolbar.add(this.pauseButton);
        JButton clearButton = new JButton("Clear");
        toolbar.add(clearButton);
        toolbar.addSeparator();
        toolbar.add(this.alwaysOnTop);

        JToolBar eventToolbar = new JToolBar();
        eventToolbar.setFloatable(false);
        eventToolbar.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
        eventToolbar.add(this.traceEvents);
        eventToolbar.addSeparator();
        eventToolbar.add(new JLabel("Event: "));
        configureTraceSelector(this.traceEventSelector, 220, "Select a registered event, or type comma-separated partial names or *");
        eventToolbar.add(this.traceEventSelector);
        eventToolbar.add(new JLabel("  Plugin: "));
        configureTraceSelector(this.tracePluginSelector, 170, "Select a plugin with registered handlers, or type comma-separated filters");
        eventToolbar.add(this.tracePluginSelector);
        eventToolbar.add(new JLabel("  Player: "));
        configureTraceSelector(this.tracePlayerSelector, 150, "Select an online player, or type comma-separated filters");
        eventToolbar.add(this.tracePlayerSelector);

        this.filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                PaperLiveDebuggerWindow.this.refreshVisibleExceptions();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                PaperLiveDebuggerWindow.this.refreshVisibleExceptions();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                PaperLiveDebuggerWindow.this.refreshVisibleExceptions();
            }
        });
        this.pauseButton.addActionListener(event -> {
            if (!this.pauseButton.isSelected()) {
                this.pausedRecordCount = 0;
                this.refreshVisibleExceptions();
            }
            this.refreshStatus();
        });
        clearButton.addActionListener(event -> {
            this.debugger.clearRecords();
            this.allRecords.clear();
            this.knownSequences.clear();
            this.visibleRecords.clear();
            this.showDetails(null);
            this.pausedRecordCount = 0;
            this.refreshStatus();
        });
        this.alwaysOnTop.addActionListener(event -> this.frame.setAlwaysOnTop(this.alwaysOnTop.isSelected()));
        this.traceEvents.addActionListener(event -> this.updateEventTraceFilter());
        this.traceEventSelector.addActionListener(event -> this.updateEventTraceFilter());
        if (this.traceEventSelector.getEditor().getEditorComponent() instanceof JTextField editor) {
            addDocumentChangeListener(editor, this::updateEventTraceFilter);
        }
        configureTraceSelectorUpdates(this.tracePluginSelector);
        configureTraceSelectorUpdates(this.tracePlayerSelector);

        JPanel controls = new JPanel(new BorderLayout());
        controls.add(toolbar, BorderLayout.NORTH);
        controls.add(eventToolbar, BorderLayout.SOUTH);
        JPanel debuggerTop = new JPanel(new BorderLayout());
        debuggerTop.add(header, BorderLayout.NORTH);
        debuggerTop.add(controls, BorderLayout.SOUTH);
        JPanel debuggerPage = new JPanel(new BorderLayout());
        debuggerPage.add(debuggerTop, BorderLayout.NORTH);
        debuggerPage.add(splitPane, BorderLayout.CENTER);

        CardLayout pageLayout = new CardLayout();
        JPanel pages = new JPanel(pageLayout);
        pages.add(this.buildSetupPage(), "setup");
        pages.add(this.buildPluginsPage(), "plugins");
        pages.add(this.buildConfigGuardPage(), "config-guard");
        pages.add(this.buildTasksPage(), "tasks");
        pages.add(this.buildScenariosPage(), "scenarios");
        pages.add(debuggerPage, "debugger");

        JToolBar navigation = new JToolBar();
        navigation.setFloatable(false);
        navigation.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel appTitle = new JLabel("PaperLive Dev Tool");
        appTitle.setFont(appTitle.getFont().deriveFont(Font.BOLD, 18F));
        navigation.add(appTitle);
        navigation.addSeparator(new Dimension(24, 0));
        JToggleButton setupNavigation = new JToggleButton("Setup", true);
        JToggleButton pluginsNavigation = new JToggleButton("Plugins");
        JToggleButton configGuardNavigation = new JToggleButton("Config Guard");
        JToggleButton tasksNavigation = new JToggleButton("Tasks");
        JToggleButton scenariosNavigation = new JToggleButton("Scenarios");
        JToggleButton debuggerNavigation = new JToggleButton("Debugger");
        ButtonGroup navigationGroup = new ButtonGroup();
        navigationGroup.add(setupNavigation);
        navigationGroup.add(pluginsNavigation);
        navigationGroup.add(configGuardNavigation);
        navigationGroup.add(tasksNavigation);
        navigationGroup.add(scenariosNavigation);
        navigationGroup.add(debuggerNavigation);
        navigation.add(setupNavigation);
        navigation.add(pluginsNavigation);
        navigation.add(configGuardNavigation);
        navigation.add(tasksNavigation);
        navigation.add(scenariosNavigation);
        navigation.add(debuggerNavigation);
        setupNavigation.addActionListener(event -> pageLayout.show(pages, "setup"));
        pluginsNavigation.addActionListener(event -> {
            pageLayout.show(pages, "plugins");
            this.refreshPlugins();
        });
        configGuardNavigation.addActionListener(event -> {
            pageLayout.show(pages, "config-guard");
            this.refreshConfigGuard();
        });
        tasksNavigation.addActionListener(event -> {
            pageLayout.show(pages, "tasks");
            this.refreshTasks();
        });
        scenariosNavigation.addActionListener(event -> {
            pageLayout.show(pages, "scenarios");
            this.refreshScenarios();
        });
        debuggerNavigation.addActionListener(event -> pageLayout.show(pages, "debugger"));

        this.installTableCellDetails(this.setupWorldTable);
        this.installTableCellDetails(this.handlerTable);
        this.installTableCellDetails(this.pluginTable);
        this.installTableCellDetails(this.configGuardTable);
        this.installTableCellDetails(this.taskTable);
        this.installTableCellDetails(this.scenarioTable);
        this.installTableCellDetails(this.scenarioStepTable);

        this.frame.add(navigation, BorderLayout.NORTH);
        this.frame.add(pages, BorderLayout.CENTER);
    }

    private JPanel buildConfigGuardPage() {
        JPanel page = new JPanel(new BorderLayout(12, 12));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Config Guard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20F));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Validate YAML from source projects and every installed plugin data folder; compare with the last known-good configuration."), BorderLayout.SOUTH);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton refreshButton = new JButton("Validate now");
        toolbar.add(refreshButton);
        toolbar.addSeparator();
        toolbar.add(this.configGuardStatus);
        refreshButton.addActionListener(event -> this.refreshConfigGuard());

        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);

        this.configGuardTable.setAutoCreateRowSorter(true);
        this.configGuardTable.setFillsViewportHeight(true);
        this.configGuardTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.configGuardTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        this.configGuardTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        this.configGuardTable.getColumnModel().getColumn(5).setPreferredWidth(420);
        page.add(new JScrollPane(this.configGuardTable), BorderLayout.CENTER);
        return page;
    }

    private JPanel buildTasksPage() {
        JPanel page = new JPanel(new BorderLayout(12, 12));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Scheduler & async inspector");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20F));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Attribute scheduled work to plugins and find slow, repeating, async or failing executions."), BorderLayout.SOUTH);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Filter: "));
        this.taskFilter.setPreferredSize(new Dimension(260, 28));
        toolbar.add(this.taskFilter);
        toolbar.addSeparator();
        toolbar.add(this.taskStatus);
        this.taskFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshTasks(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshTasks(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshTasks(); }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);

        this.taskTable.setAutoCreateRowSorter(true);
        this.taskTable.setFillsViewportHeight(true);
        this.taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.taskTable.getColumnModel().getColumn(4).setPreferredWidth(230);
        this.taskTable.getColumnModel().getColumn(6).setPreferredWidth(210);
        this.taskTable.getColumnModel().getColumn(9).setPreferredWidth(360);
        page.add(new JScrollPane(this.taskTable), BorderLayout.CENTER);
        return page;
    }

    private JPanel buildScenariosPage() {
        JPanel page = new JPanel(new BorderLayout(12, 12));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Scenario recorder & replay");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20F));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Record a manual flow once; replay commands and verify final player state after each refresh."), BorderLayout.SOUTH);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Player: "));
        this.scenarioPlayer.setPreferredSize(new Dimension(150, 28));
        toolbar.add(this.scenarioPlayer);
        toolbar.add(new JLabel("  Name: "));
        this.scenarioName.setPreferredSize(new Dimension(180, 28));
        toolbar.add(this.scenarioName);
        JButton start = new JButton("Start recording");
        JButton stop = new JButton("Stop & save");
        JButton open = new JButton("Open selected");
        JButton delete = new JButton("Delete selected");
        JButton replay = new JButton("Replay selected");
        JButton bisect = new JButton("Bisect plugin conflict");
        toolbar.addSeparator();
        toolbar.add(this.scenarioStatus);
        JToolBar actions = new JToolBar();
        actions.setFloatable(false);
        actions.add(start);
        actions.add(stop);
        actions.addSeparator();
        actions.add(open);
        actions.add(delete);
        actions.addSeparator();
        actions.add(replay);
        actions.add(this.scenarioContinueButton);
        actions.add(this.scenarioStopButton);
        actions.addSeparator();
        actions.add(bisect);
        start.addActionListener(event -> this.startScenarioRecording());
        stop.addActionListener(event -> this.stopScenarioRecording());
        open.addActionListener(event -> this.openScenarioDetails());
        delete.addActionListener(event -> this.deleteSelectedScenario());
        replay.addActionListener(event -> this.replayScenario());
        this.scenarioContinueButton.addActionListener(event -> this.continueScenarioReplay());
        this.scenarioStopButton.addActionListener(event -> this.stopScenarioReplay());
        this.scenarioContinueButton.setEnabled(false);
        this.scenarioStopButton.setEnabled(false);
        bisect.addActionListener(event -> this.bisectScenarioConflict());

        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.NORTH);
        JPanel controls = new JPanel(new BorderLayout());
        controls.add(toolbar, BorderLayout.NORTH);
        controls.add(actions, BorderLayout.SOUTH);
        top.add(controls, BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);
        this.scenarioTable.setAutoCreateRowSorter(true);
        this.scenarioTable.setFillsViewportHeight(true);
        this.scenarioTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.scenarioTable.getSelectionModel().addListSelectionListener(event -> {
            int row = this.scenarioTable.getSelectedRow();
            if (row >= 0) {
                String name = String.valueOf(this.scenarioTable.getValueAt(row, 0));
                if (this.activeScenarioReplay != null && !this.activeScenarioReplay.scenario().name().equals(name)) {
                    this.stopScenarioReplay();
                }
                this.scenarioName.setText(name);
                this.loadScenarioSteps(name);
            }
        });
        this.scenarioStepTable.setFillsViewportHeight(true);
        this.scenarioStepTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.scenarioStepTable.getColumnModel().getColumn(0).setMaxWidth(58);
        this.scenarioStepTable.getColumnModel().getColumn(1).setMaxWidth(45);
        this.scenarioStepTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        this.scenarioStepTable.getColumnModel().getColumn(3).setPreferredWidth(180);
        this.scenarioStepTable.getColumnModel().getColumn(4).setPreferredWidth(500);
        this.scenarioStepTable.getColumnModel().getColumn(5).setPreferredWidth(260);
        this.scenarioStepTable.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
                Component component = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                setHorizontalAlignment(JLabel.CENTER);
                setForeground(new Color(220, 55, 55));
                setFont(getFont().deriveFont(Font.BOLD, 18F));
                return component;
            }
        });
        this.scenarioStepTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 1) {
                    return;
                }
                int viewRow = scenarioStepTable.rowAtPoint(event.getPoint());
                int viewColumn = scenarioStepTable.columnAtPoint(event.getPoint());
                if (viewRow >= 0 && viewColumn == 0) {
                    toggleScenarioBreakpoint(scenarioStepTable.convertRowIndexToModel(viewRow));
                }
            }
        });
        JPanel steps = new JPanel(new BorderLayout(4, 4));
        steps.setBorder(BorderFactory.createTitledBorder("Recorded actions and replay result"));
        steps.add(new JScrollPane(this.scenarioStepTable), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(this.scenarioTable), steps);
        split.setResizeWeight(0.42D);
        split.setDividerLocation(235);
        page.add(split, BorderLayout.CENTER);
        return page;
    }

    private void deleteSelectedScenario() {
        int selectedRow = this.scenarioTable.getSelectedRow();
        if (selectedRow < 0) {
            this.scenarioStatus.setText("Select a scenario to delete");
            return;
        }
        String name = String.valueOf(this.scenarioTable.getValueAt(selectedRow, 0));
        int answer = JOptionPane.showConfirmDialog(
            this.frame,
            "Permanently delete scenario '" + name + "'?\nThis removes its recording and saved breakpoints.",
            "Delete scenario",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            if (this.activeScenarioReplay != null && this.activeScenarioReplay.scenario().name().equals(name)) {
                this.stopScenarioReplay();
            }
            boolean deleted = PaperLiveScenarioRecorder.delete(this.scenarioDirectory(), name);
            if (this.lastScenarioReplay != null && this.lastScenarioReplay.scenarioName().equals(name)) {
                this.lastScenarioReplay = null;
            }
            this.scenarioStepModel.setRowCount(0);
            this.scenarioName.setText("");
            this.refreshScenarios();
            this.scenarioStatus.setText(deleted ? "Deleted scenario " + name : "Scenario file was already removed");
        } catch (IOException | RuntimeException exception) {
            this.scenarioStatus.setText("Cannot delete scenario: " + exception.getMessage());
        }
    }

    private void openScenarioDetails() {
        String name = this.scenarioName.getText().strip();
        if (name.isEmpty()) {
            this.scenarioStatus.setText("Select a scenario");
            return;
        }
        try {
            PaperLiveScenarioRecorder.Scenario scenario = PaperLiveScenarioRecorder.load(this.scenarioDirectory().resolve(name + ".yml"));
            StringBuilder details = new StringBuilder();
            details.append("Scenario: ").append(scenario.name()).append('\n');
            details.append("Recorded player: ").append(scenario.playerName()).append('\n');
            details.append("Initial state: ").append(scenario.initial()).append('\n');
            details.append("Expected state: ").append(scenario.expected()).append("\n\n");
            for (int index = 0; index < scenario.steps().size(); index++) {
                PaperLiveScenarioRecorder.Step step = scenario.steps().get(index);
                details.append(index + 1).append(scenario.breakpoints().contains(index) ? ". ● BREAKPOINT " : ". ")
                    .append(step.type()).append(" — ").append(step.value()).append('\n');
                step.details().forEach((key, value) -> details.append("    ").append(key).append(": ").append(value).append('\n'));
            }
            if (this.lastScenarioReplay != null && this.lastScenarioReplay.scenarioName().equals(name)) {
                details.append("\nLast replay:\n");
                this.lastScenarioReplay.steps().forEach(result -> details.append(result.stepIndex() + 1).append(". ")
                    .append(result.status()).append(" — ").append(result.message()).append('\n'));
                if (!this.lastScenarioReplay.failures().isEmpty()) {
                    details.append("Failures:\n");
                    this.lastScenarioReplay.failures().forEach(failure -> details.append("- ").append(failure).append('\n'));
                }
            }
            JTextArea content = textArea();
            content.setText(details.toString());
            content.setCaretPosition(0);
            JScrollPane scroll = new JScrollPane(content);
            scroll.setPreferredSize(new Dimension(900, 620));
            JOptionPane.showMessageDialog(this.frame, scroll, "Scenario details — " + name, JOptionPane.PLAIN_MESSAGE);
        } catch (IOException exception) {
            this.scenarioStatus.setText("Cannot open scenario: " + exception.getMessage());
        }
    }

    private void loadScenarioSteps(String name) {
        this.scenarioStepModel.setRowCount(0);
        try {
            PaperLiveScenarioRecorder.Scenario scenario = PaperLiveScenarioRecorder.load(this.scenarioDirectory().resolve(name + ".yml"));
            Map<Integer, PaperLiveScenarioRecorder.ReplayStepResult> replayResults = new java.util.HashMap<>();
            if (this.lastScenarioReplay != null && this.lastScenarioReplay.scenarioName().equals(name)) {
                this.lastScenarioReplay.steps().forEach(result -> replayResults.put(result.stepIndex(), result));
            }
            for (int index = 0; index < scenario.steps().size(); index++) {
                PaperLiveScenarioRecorder.Step step = scenario.steps().get(index);
                PaperLiveScenarioRecorder.ReplayStepResult replay = replayResults.get(index);
                String replayText = replay == null ? "Not replayed yet" : replay.status() + " — " + replay.message();
                this.scenarioStepModel.addRow(new Object[]{scenario.breakpoints().contains(index) ? "●" : "", index + 1, step.type(), step.value(), formatScenarioDetails(step.details()), replayText});
            }
        } catch (IOException exception) {
            this.scenarioStepModel.addRow(new Object[]{"", "—", "INVALID", name, exception.getMessage(), "Cannot replay"});
        }
    }

    private void toggleScenarioBreakpoint(int modelRow) {
        String name = this.scenarioName.getText().strip();
        if (name.isEmpty()) {
            return;
        }
        Object rawStep = this.scenarioStepModel.getValueAt(modelRow, 1);
        if (!(rawStep instanceof Number number)) {
            return;
        }
        int stepIndex = number.intValue() - 1;
        boolean enabled = !"●".equals(this.scenarioStepModel.getValueAt(modelRow, 0));
        try {
            PaperLiveScenarioRecorder.setBreakpoint(this.scenarioDirectory().resolve(name + ".yml"), stepIndex, enabled);
            this.loadScenarioSteps(name);
            this.scenarioStatus.setText((enabled ? "● Breakpoint set before step " : "Breakpoint removed from step ") + (stepIndex + 1));
        } catch (IOException | RuntimeException exception) {
            this.scenarioStatus.setText("Cannot update breakpoint: " + exception.getMessage());
        }
    }

    private static String formatScenarioDetails(Map<String, String> details) {
        return details.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(" • "));
    }

    private void startScenarioRecording() {
        org.bukkit.entity.Player player = this.selectedScenarioPlayer();
        if (player == null) {
            this.scenarioStatus.setText("Select an online player");
            return;
        }
        try {
            PaperLiveScenarioRecorder.instance().start(player);
            this.scenarioStatus.setText("● Recording " + player.getName());
        } catch (RuntimeException exception) {
            this.scenarioStatus.setText(exception.getMessage());
        }
    }

    private void stopScenarioRecording() {
        org.bukkit.entity.Player player = this.selectedScenarioPlayer();
        if (player == null) {
            this.scenarioStatus.setText("The recorded player is not online");
            return;
        }
        try {
            PaperLiveScenarioRecorder.Scenario saved = PaperLiveScenarioRecorder.instance().stop(
                this.scenarioName.getText().strip(), this.scenarioDirectory(), player
            );
            this.scenarioStatus.setText("Saved " + saved.steps().size() + " steps");
            this.refreshScenarios();
        } catch (Exception exception) {
            this.scenarioStatus.setText(exception.getMessage());
        }
    }

    private void replayScenario() {
        org.bukkit.entity.Player player = this.selectedScenarioPlayer();
        String name = this.scenarioName.getText().strip();
        if (player == null || name.isEmpty()) {
            this.scenarioStatus.setText("Select a player and scenario");
            return;
        }
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            return;
        }
        minecraftServer.execute(() -> {
            try {
                PaperLiveScenarioRecorder.ReplaySession session = PaperLiveScenarioRecorder.instance()
                    .beginReplay(this.scenarioDirectory().resolve(name + ".yml"), player);
                PaperLiveScenarioRecorder.ReplayProgress progress = session.advance(session.scenario().breakpoints(), false);
                SwingUtilities.invokeLater(() -> {
                    this.activeScenarioReplay = progress.completed() ? null : session;
                    this.showScenarioReplayProgress(name, progress);
                });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> this.scenarioStatus.setText(exception.getMessage()));
            }
        });
    }

    private void continueScenarioReplay() {
        PaperLiveScenarioRecorder.ReplaySession session = this.activeScenarioReplay;
        if (session == null) {
            return;
        }
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            return;
        }
        String name = session.scenario().name();
        this.scenarioContinueButton.setEnabled(false);
        minecraftServer.execute(() -> {
            try {
                Set<Integer> breakpoints = PaperLiveScenarioRecorder.load(this.scenarioDirectory().resolve(name + ".yml")).breakpoints();
                PaperLiveScenarioRecorder.ReplayProgress progress = session.advance(breakpoints, true);
                SwingUtilities.invokeLater(() -> {
                    this.activeScenarioReplay = progress.completed() ? null : session;
                    this.showScenarioReplayProgress(name, progress);
                });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> {
                    this.scenarioContinueButton.setEnabled(true);
                    this.scenarioStatus.setText("Cannot continue replay: " + exception.getMessage());
                });
            }
        });
    }

    private void stopScenarioReplay() {
        if (this.activeScenarioReplay != null) {
            this.activeScenarioReplay = null;
            this.scenarioStatus.setText("Replay stopped; remaining steps were not executed");
        }
        this.scenarioContinueButton.setEnabled(false);
        this.scenarioStopButton.setEnabled(false);
    }

    private void showScenarioReplayProgress(String name, PaperLiveScenarioRecorder.ReplayProgress progress) {
        PaperLiveScenarioRecorder.ReplayResult result = progress.result();
        this.lastScenarioReplay = result;
        this.loadScenarioSteps(name);
        this.scenarioContinueButton.setEnabled(!progress.completed());
        this.scenarioStopButton.setEnabled(!progress.completed());
        if (!progress.completed()) {
            PaperLiveScenarioRecorder.Step next = this.activeScenarioReplay == null ? null : this.activeScenarioReplay.scenario().steps().get(progress.nextStepIndex());
            this.scenarioStatus.setText("● Paused before step " + (progress.nextStepIndex() + 1) + (next == null ? "" : " — " + next.type() + " / " + next.value()));
        } else {
            this.scenarioStatus.setText(result.successful()
                ? "✓ Passed • " + result.commandsExecuted() + " commands • " + result.actionsReplayed() + " actions replayed • " + result.observationsSkipped() + " observations"
                : "✗ " + String.join("; ", result.failures()));
        }
    }

    private void bisectScenarioConflict() {
        org.bukkit.entity.Player player = this.selectedScenarioPlayer();
        String name = this.scenarioName.getText().strip();
        String snapshot = this.setupSnapshotName.getText().strip();
        String world = this.setupWorldName.getText().strip();
        if (player == null || name.isEmpty() || snapshot.isEmpty() || world.isEmpty()) {
            this.scenarioStatus.setText("Select player/scenario and configure a dev world + snapshot on Setup");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(
            this.frame,
            "Bisect source plugins using scenario '" + name + "'?\nSnapshot '" + snapshot + "' will be restored before every iteration.",
            "Confirm plugin conflict bisect",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            return;
        }
        this.scenarioStatus.setText("Bisect running...");
        minecraftServer.execute(() -> {
            try {
                PaperLiveConflictBisector.Result result = PaperLiveConflictBisector.run(
                    message -> this.appendSetupOutput(message),
                    snapshot,
                    world,
                    this.scenarioDirectory().resolve(name + ".yml"),
                    player
                );
                SwingUtilities.invokeLater(() -> this.scenarioStatus.setText(
                    (result.confirmed() ? "✓ " : "⚠ ") + result.explanation() + " • " + result.iterations() + " iterations"
                ));
            } catch (Throwable throwable) {
                SwingUtilities.invokeLater(() -> this.scenarioStatus.setText("Bisect failed: " + throwable.getMessage()));
            }
        });
    }

    private void refreshScenarios() {
        this.scenarioModel.setRowCount(0);
        for (String name : PaperLiveScenarioRecorder.list(this.scenarioDirectory())) {
            try {
                PaperLiveScenarioRecorder.Scenario scenario = PaperLiveScenarioRecorder.load(this.scenarioDirectory().resolve(name + ".yml"));
                long commands = scenario.steps().stream().filter(step -> step.type().equals("COMMAND")).count();
                this.scenarioModel.addRow(new Object[]{
                    name, scenario.playerName(), commands, scenario.steps().size() - commands, scenario.expected().world(), scenario.expected().inventory()
                });
            } catch (IOException ignored) {
                this.scenarioModel.addRow(new Object[]{name, "—", "—", "—", "—", "Invalid scenario file"});
            }
        }
    }

    private org.bukkit.entity.@Nullable Player selectedScenarioPlayer() {
        Object selected = this.scenarioPlayer.getSelectedItem();
        return selected == null ? null : Bukkit.getPlayerExact(String.valueOf(selected));
    }

    private java.nio.file.Path scenarioDirectory() {
        PluginInitializerManager initializer = PluginInitializerManager.instance();
        return initializer == null
            ? Bukkit.getWorldContainer().toPath().resolve("plugins/PaperLive/scenarios")
            : initializer.pluginDirectoryPath().resolve("PaperLive").resolve("scenarios");
    }

    private void refreshTasks() {
        String filter = this.taskFilter.getText().strip().toLowerCase(Locale.ROOT);
        List<PaperLiveTaskTrace> traces = this.debugger.recentTaskTraces();
        this.taskModel.setRowCount(0);
        long slow = 0;
        long failed = 0;
        for (PaperLiveTaskTrace trace : traces) {
            if (trace.durationNanos() >= 50_000_000L) {
                slow++;
            }
            if (!trace.successful()) {
                failed++;
            }
            String searchable = String.join(" ", trace.pluginName(), trace.schedulerType(), trace.taskId(), trace.taskClass(), trace.threadName(), trace.registrationSite(), String.valueOf(trace.failure())).toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !searchable.contains(filter)) {
                continue;
            }
            this.taskModel.addRow(new Object[]{
                TIME_FORMAT.format(Instant.ofEpochMilli(trace.occurredAtEpochMillis())),
                trace.pluginName(),
                trace.schedulerType(),
                trace.taskId(),
                simpleName(trace.taskClass()),
                formatDuration(trace.durationNanos()),
                trace.threadName(),
                trace.repeating(),
                trace.successful() ? trace.durationNanos() >= 50_000_000L ? "SLOW" : "OK" : "FAILED: " + trace.failure(),
                trace.registrationSite()
            });
        }
        this.taskStatus.setText(traces.size() + " executions • " + slow + " slow (≥50 ms) • " + failed + " failed");
    }

    private void refreshConfigGuard() {
        if (!this.configGuardRefreshPending.compareAndSet(false, true) || this.closed.get()) {
            return;
        }
        PluginInitializerManager initializer = PluginInitializerManager.instance();
        if (initializer == null) {
            this.configGuardRefreshPending.set(false);
            this.configGuardStatus.setText("Project discovery is not ready");
            return;
        }
        this.configGuardStatus.setText("Validating...");
        Thread.ofVirtual().name("PaperLive Config Guard UI").start(() -> {
            PaperLiveConfigGuard.Inspection inspection = PaperLiveConfigGuard.inspect(initializer.pluginDirectoryPath());
            Map<String, PaperLiveConfigGuard.ConfigChange> changes = new java.util.HashMap<>();
            for (PaperLiveConfigGuard.ConfigChange change : inspection.changes()) {
                changes.put(change.project().toLowerCase(Locale.ROOT) + '\u0000' + change.relativePath().toLowerCase(Locale.ROOT), change);
            }
            SwingUtilities.invokeLater(() -> {
                this.configGuardModel.setRowCount(0);
                Set<String> diagnosticFiles = new HashSet<>();
                for (PaperLiveConfigGuard.Diagnostic diagnostic : inspection.diagnostics()) {
                    String relative = configRelativePath(diagnostic, initializer.pluginDirectoryPath());
                    String key = diagnostic.project().toLowerCase(Locale.ROOT) + '\u0000' + relative.toLowerCase(Locale.ROOT);
                    diagnosticFiles.add(key);
                    PaperLiveConfigGuard.ConfigChange change = changes.get(key);
                    this.configGuardModel.addRow(new Object[]{
                        diagnostic.project(),
                        relative,
                        diagnostic.severity(),
                        change == null ? "—" : change.change(),
                        diagnostic.line() == 0 ? "—" : diagnostic.line() + (diagnostic.column() == 0 ? "" : ":" + diagnostic.column()),
                        diagnostic.message()
                    });
                }
                for (PaperLiveConfigGuard.ConfigChange change : inspection.changes()) {
                    String key = change.project().toLowerCase(Locale.ROOT) + '\u0000' + change.relativePath().toLowerCase(Locale.ROOT);
                    if (!diagnosticFiles.contains(key)) {
                        this.configGuardModel.addRow(new Object[]{
                            change.project(), change.relativePath(), "VALID", change.change(), "—", ""
                        });
                    }
                }
                long errors = inspection.diagnostics().stream().filter(diagnostic -> diagnostic.severity() == PaperLiveConfigGuard.Severity.ERROR).count();
                long changed = inspection.changes().stream().filter(change -> change.change() != PaperLiveConfigGuard.Change.UNCHANGED).count();
                this.configGuardStatus.setText(errors == 0
                    ? "Valid • " + changed + " changed since last successful refresh"
                    : errors + " error(s) • refresh will be blocked");
                this.configGuardRefreshPending.set(false);
            });
        });
    }

    private static String configRelativePath(PaperLiveConfigGuard.Diagnostic diagnostic, java.nio.file.Path pluginDirectory) {
        java.nio.file.Path path = diagnostic.file();
        if (diagnostic.project().startsWith("Installed: ")) {
            java.nio.file.Path root = pluginDirectory.resolve(diagnostic.project().substring("Installed: ".length())).toAbsolutePath().normalize();
            java.nio.file.Path normalized = path.toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                return root.relativize(normalized).toString().replace('\\', '/');
            }
        }
        String normalized = path.toString().replace('\\', '/');
        String marker = "/src/main/resources/";
        int markerIndex = normalized.indexOf(marker);
        return markerIndex < 0 ? normalized : normalized.substring(markerIndex + marker.length());
    }

    private JPanel buildPluginsPage() {
        JPanel page = new JPanel(new BorderLayout(12, 12));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Plugin manager");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20F));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Manage loaded plugins, source projects and plugin JARs from one place."), BorderLayout.SOUTH);

        this.pluginTable.setAutoCreateRowSorter(true);
        this.pluginTable.setFillsViewportHeight(true);
        this.pluginTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.pluginTable.getColumnModel().getColumn(0).setPreferredWidth(190);
        this.pluginTable.getColumnModel().getColumn(4).setPreferredWidth(230);
        this.pluginTable.getColumnModel().getColumn(5).setPreferredWidth(230);
        this.pluginTable.getColumnModel().getColumn(6).setPreferredWidth(320);
        this.pluginTable.getSelectionModel().addListSelectionListener(event -> this.updatePluginActions());

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Search: "));
        this.pluginFilter.setPreferredSize(new Dimension(260, 28));
        toolbar.add(this.pluginFilter);
        toolbar.addSeparator();
        toolbar.add(this.pluginLoadButton);
        toolbar.add(this.pluginUnloadButton);
        toolbar.add(this.pluginEnableButton);
        toolbar.add(this.pluginDisableButton);
        JButton refreshButton = new JButton("Refresh list");
        toolbar.addSeparator();
        toolbar.add(refreshButton);
        toolbar.addSeparator();
        toolbar.add(this.pluginStatus);
        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);
        page.add(new JScrollPane(this.pluginTable), BorderLayout.CENTER);

        this.pluginFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshPlugins(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshPlugins(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshPlugins(); }
        });
        refreshButton.addActionListener(event -> this.refreshPlugins());
        this.pluginLoadButton.addActionListener(event -> this.runPluginAction("load"));
        this.pluginUnloadButton.addActionListener(event -> this.runPluginAction("unload"));
        this.pluginEnableButton.addActionListener(event -> this.runPluginAction("enable"));
        this.pluginDisableButton.addActionListener(event -> this.runPluginAction("disable"));
        this.updatePluginActions();
        return page;
    }

    private void runPluginAction(String action) {
        int selectedViewRow = this.pluginTable.getSelectedRow();
        if (selectedViewRow < 0) return;
        int row = this.pluginTable.convertRowIndexToModel(selectedViewRow);
        String name = String.valueOf(this.pluginModel.getValueAt(row, 0));
        switch (action) {
            case "load" -> PaperLivePluginActions.load(name);
            case "unload" -> PaperLivePluginActions.unload(name);
            case "enable" -> PaperLivePluginActions.enable(name);
            case "disable" -> PaperLivePluginActions.disable(name);
            default -> { return; }
        }
        this.pluginStatus.setText(action + " requested for " + name);
        Timer refresh = new Timer(700, event -> this.refreshPlugins());
        refresh.setRepeats(false);
        refresh.start();
    }

    private void updatePluginActions() {
        int selectedViewRow = this.pluginTable.getSelectedRow();
        boolean selected = selectedViewRow >= 0;
        this.pluginLoadButton.setEnabled(selected);
        this.pluginUnloadButton.setEnabled(selected);
        this.pluginEnableButton.setEnabled(selected);
        this.pluginDisableButton.setEnabled(selected);
        if (selected) {
            int row = this.pluginTable.convertRowIndexToModel(selectedViewRow);
            String status = String.valueOf(this.pluginModel.getValueAt(row, 2));
            this.pluginLoadButton.setEnabled(!status.equals("Loaded"));
            this.pluginUnloadButton.setEnabled(status.equals("Loaded"));
            this.pluginEnableButton.setEnabled(!status.equals("Loaded"));
            this.pluginDisableButton.setEnabled(status.equals("Loaded"));
        }
    }

    private void refreshPlugins() {
        if (!this.pluginRefreshPending.compareAndSet(false, true) || this.closed.get()) return;
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) { this.pluginRefreshPending.set(false); return; }
        minecraftServer.execute(() -> {
            List<PluginSnapshot> snapshots = new ArrayList<>();
            try {
                Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                List<PaperLivePluginDependencies.Descriptor> descriptors = new ArrayList<>();
                Map<String, PaperLivePluginDependencies.PluginJar> availableJars = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                Map<String, String> locations = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (Plugin plugin : this.server.getPluginManager().getPlugins()) {
                    names.add(plugin.getPluginMeta().getName());
                    locations.put(plugin.getPluginMeta().getName(), plugin.getDataFolder().toPath().toString());
                    descriptors.add(new PaperLivePluginDependencies.Descriptor(
                        plugin.getPluginMeta().getName(),
                        plugin.getPluginMeta().getPluginDependencies(),
                        plugin.getPluginMeta().getPluginSoftDependencies(),
                        plugin.getPluginMeta().getProvidedPlugins()
                    ));
                }
                PluginInitializerManager initializer = PluginInitializerManager.instance();
                java.nio.file.Path pluginDirectory = initializer == null ? null : initializer.pluginDirectoryPath();
                if (pluginDirectory != null) {
                    this.collectPluginJars(pluginDirectory, availableJars);
                    this.collectPluginJars(pluginDirectory.resolve(".paperlive-runtime"), availableJars);
                    Set<String> loadedNames = names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                    for (PaperLivePluginDependencies.PluginJar pluginJar : availableJars.values()) {
                        names.add(pluginJar.name());
                        locations.putIfAbsent(pluginJar.name(), pluginJar.path().toString());
                        if (!loadedNames.contains(pluginJar.name().toLowerCase(Locale.ROOT))) {
                            descriptors.add(pluginJar.descriptor());
                        }
                    }
                    java.nio.file.Path projects = pluginDirectory.resolve("PaperLive").resolve("projects");
                    if (java.nio.file.Files.isDirectory(projects)) try (var paths = java.nio.file.Files.list(projects)) {
                        paths.filter(java.nio.file.Files::isDirectory).forEach(path -> {
                            String projectName = path.getFileName().toString();
                            java.nio.file.Path runtimeJar = pluginDirectory.resolve(".paperlive-runtime").resolve("paperlive-" + projectName + ".jar");
                            PaperLivePluginDependencies.PluginJar metadata = this.cachedPluginJar(runtimeJar);
                            String displayName = metadata == null ? projectName : metadata.name();
                            names.add(displayName);
                            locations.put(displayName, path.toString());
                        });
                    }
                }
                Map<String, PaperLivePluginDependencies.Relationship> relationships = PaperLivePluginDependencies.resolve(descriptors);
                for (String name : names) {
                    Plugin loaded = this.server.getPluginManager().getPlugin(name);
                    PaperLivePluginDependencies.PluginJar availableJar = availableJars.get(name);
                    String type = loaded != null ? "Loaded plugin" : availableJar == null ? "Source project" : "Available JAR / project";
                    String version = loaded != null ? loaded.getPluginMeta().getVersion() : availableJar == null ? "—" : availableJar.version();
                    PaperLivePluginDependencies.Relationship relationship = relationships.get(name.toLowerCase(Locale.ROOT));
                    String dependsOn = relationship == null ? "—" : PaperLivePluginDependencies.dependsOnDisplay(relationship);
                    String dependents = relationship == null ? "—" : PaperLivePluginDependencies.dependentsDisplay(relationship);
                    snapshots.add(new PluginSnapshot(
                        name,
                        type,
                        loaded == null ? "Available" : "Loaded",
                        version,
                        dependsOn,
                        dependents,
                        locations.getOrDefault(name, pluginDirectory == null ? "—" : pluginDirectory.toString())
                    ));
                }
            } catch (Throwable throwable) {
                this.pluginStatus.setText("Could not read plugins: " + throwable.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                String filter = this.pluginFilter.getText().strip().toLowerCase(Locale.ROOT);
                String selectedPlugin = tableRowKey(this.pluginTable, 0);
                this.pluginModel.setRowCount(0);
                for (PluginSnapshot snapshot : snapshots) if (filter.isEmpty() || snapshot.searchText().contains(filter)) {
                    this.pluginModel.addRow(new Object[]{
                        snapshot.name(), snapshot.type(), snapshot.status(), snapshot.version(), snapshot.dependsOn(), snapshot.dependents(), snapshot.location()
                    });
                }
                restoreTableSelection(this.pluginTable, selectedPlugin, 0);
                this.pluginStatus.setText(snapshots.size() + " plugins / projects detected");
                this.pluginRefreshPending.set(false);
                this.updatePluginActions();
            });
        });
    }

    private void collectPluginJars(java.nio.file.Path directory, Map<String, PaperLivePluginDependencies.PluginJar> output) {
        if (!java.nio.file.Files.isDirectory(directory)) {
            return;
        }
        try (var paths = java.nio.file.Files.list(directory)) {
            paths.filter(java.nio.file.Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .forEach(path -> {
                    PaperLivePluginDependencies.PluginJar pluginJar = this.cachedPluginJar(path);
                    if (pluginJar != null) {
                        output.putIfAbsent(pluginJar.name(), pluginJar);
                    }
                });
        } catch (IOException ignored) {
        }
    }

    private PaperLivePluginDependencies.@Nullable PluginJar cachedPluginJar(java.nio.file.Path path) {
        try {
            if (!java.nio.file.Files.isRegularFile(path)) {
                return null;
            }
            long modified = java.nio.file.Files.getLastModifiedTime(path).toMillis();
            long size = java.nio.file.Files.size(path);
            CachedPluginJar cached = this.pluginJarCache.get(path);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.metadata();
            }
            PaperLivePluginDependencies.PluginJar metadata = PaperLivePluginDependencies.readPluginJar(path);
            this.pluginJarCache.put(path, new CachedPluginJar(modified, size, metadata));
            return metadata;
        } catch (IOException ignored) {
            this.pluginJarCache.remove(path);
            return null;
        }
    }

    private void installTableCellDetails(JTable table) {
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int viewRow = table.rowAtPoint(event.getPoint());
                int viewColumn = table.columnAtPoint(event.getPoint());
                if (viewRow < 0 || viewColumn < 0 || table == scenarioStepTable && viewColumn == 0) {
                    return;
                }
                Object value = table.getValueAt(viewRow, viewColumn);
                String content = value == null ? "" : String.valueOf(value);
                String column = String.valueOf(table.getColumnModel().getColumn(viewColumn).getHeaderValue());
                JTextArea details = textArea();
                details.setLineWrap(true);
                details.setWrapStyleWord(true);
                details.setText(content);
                details.setCaretPosition(0);
                JScrollPane scroll = new JScrollPane(details);
                scroll.setPreferredSize(new Dimension(760, Math.min(520, Math.max(180, 80 + content.length() / 3))));
                JOptionPane.showMessageDialog(frame, scroll, column + " — full value", JOptionPane.PLAIN_MESSAGE);
            }
        });
    }

    private static String tableRowKey(JTable table, int... columns) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return "";
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        StringBuilder key = new StringBuilder();
        for (int column : columns) {
            if (key.length() > 0) {
                key.append('\u0000');
            }
            key.append(String.valueOf(table.getModel().getValueAt(modelRow, column)));
        }
        return key.toString();
    }

    private static void restoreTableSelection(JTable table, String key, int... columns) {
        if (key.isEmpty()) {
            return;
        }
        for (int modelRow = 0; modelRow < table.getModel().getRowCount(); modelRow++) {
            StringBuilder candidate = new StringBuilder();
            for (int column : columns) {
                if (candidate.length() > 0) {
                    candidate.append('\u0000');
                }
                candidate.append(String.valueOf(table.getModel().getValueAt(modelRow, column)));
            }
            if (key.contentEquals(candidate)) {
                int viewRow = table.convertRowIndexToView(modelRow);
                if (viewRow >= 0) {
                    table.setRowSelectionInterval(viewRow, viewRow);
                }
                return;
            }
        }
    }

    private record PluginSnapshot(String name, String type, String status, String version, String dependsOn, String dependents, String location) {

        private String searchText() {
            return String.join(" ", this.name, this.type, this.status, this.version, this.dependsOn, this.dependents, this.location).toLowerCase(Locale.ROOT);
        }
    }

    private record CachedPluginJar(long modified, long size, PaperLivePluginDependencies.PluginJar metadata) {
    }

    private JPanel buildSetupPage() {
        JPanel page = new JPanel(new BorderLayout(12, 12));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Development server setup");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20F));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Create and reset disposable development worlds without leaving the tool."), BorderLayout.SOUTH);
        page.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("World options"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 8, 5, 8);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        addSetupRow(form, constraints, 0, "World name", this.setupWorldName);
        addSetupRow(form, constraints, 1, "Preset", this.setupPreset);
        addSetupRow(form, constraints, 2, "Seed", this.setupSeed);
        addSetupRow(form, constraints, 3, "Environment", this.setupEnvironment);

        JPanel toggles = new JPanel(new java.awt.GridLayout(0, 2, 8, 4));
        toggles.add(this.setupStructures);
        toggles.add(this.setupHardcore);
        toggles.add(this.setupBonusChest);
        toggles.add(this.setupPlatform);
        addSetupRow(form, constraints, 4, "Options", toggles);

        this.setupGeneratorSettings.setFont(MONOSPACED);
        this.setupGeneratorSettings.setLineWrap(true);
        this.setupGeneratorSettings.setWrapStyleWord(true);
        this.setupGeneratorSettings.setToolTipText("Optional Minecraft flat generator JSON. Leave empty for the default flat preset.");
        addSetupRow(form, constraints, 5, "Flat settings JSON", new JScrollPane(this.setupGeneratorSettings));
        addSetupRow(form, constraints, 6, "Online player", this.setupPlayer);
        addSetupRow(form, constraints, 7, "Snapshot name", this.setupSnapshotName);

        JPanel actions = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
        actions.add(this.setupCreateButton);
        actions.add(this.setupResetButton);
        actions.add(this.setupTeleportButton);
        actions.add(this.setupSnapshotCreateButton);
        actions.add(this.setupSnapshotRestoreButton);
        JButton refreshButton = new JButton("Refresh worlds");
        actions.add(refreshButton);
        constraints.gridx = 0;
        constraints.gridy = 8;
        constraints.gridwidth = 2;
        constraints.weighty = 1.0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        form.add(actions, constraints);

        this.setupWorldTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.setupWorldTable.setAutoCreateRowSorter(true);
        this.setupWorldTable.setFillsViewportHeight(true);
        this.setupWorldTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                int selectedViewRow = this.setupWorldTable.getSelectedRow();
                if (selectedViewRow >= 0) {
                    int row = this.setupWorldTable.convertRowIndexToModel(selectedViewRow);
                    this.setupWorldName.setText(String.valueOf(this.setupWorldModel.getValueAt(row, 0)));
                    String type = String.valueOf(this.setupWorldModel.getValueAt(row, 1)).toLowerCase(Locale.ROOT);
                    this.setupPreset.setSelectedItem(type.equals("large_biomes") ? "large_biomes" : type);
                    this.setupEnvironment.setSelectedItem(String.valueOf(this.setupWorldModel.getValueAt(row, 2)).toLowerCase(Locale.ROOT));
                    this.setupSeed.setText(String.valueOf(this.setupWorldModel.getValueAt(row, 3)));
                }
            }
        });

        JScrollPane worlds = new JScrollPane(this.setupWorldTable);
        worlds.setBorder(BorderFactory.createTitledBorder("Loaded worlds"));
        JSplitPane setupSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, form, worlds);
        setupSplit.setResizeWeight(0.43);
        setupSplit.setDividerLocation(500);
        page.add(setupSplit, BorderLayout.CENTER);

        this.setupOutput.setText("Ready. Select a loaded world to copy its basic values, or enter a new name.\n");
        JScrollPane output = new JScrollPane(this.setupOutput);
        output.setBorder(BorderFactory.createTitledBorder("Setup output"));
        output.setPreferredSize(new Dimension(900, 170));
        page.add(output, BorderLayout.SOUTH);

        this.setupPreset.addActionListener(event -> this.updateSetupOptionState());
        this.setupCreateButton.addActionListener(event -> this.runSetupOperation("create"));
        this.setupResetButton.addActionListener(event -> this.runSetupOperation("reset"));
        this.setupSnapshotCreateButton.addActionListener(event -> this.runSnapshotOperation(false));
        this.setupSnapshotRestoreButton.addActionListener(event -> this.runSnapshotOperation(true));
        this.setupTeleportButton.addActionListener(event -> this.teleportSetupPlayer());
        refreshButton.addActionListener(event -> this.refreshSetupWorlds());
        this.updateSetupOptionState();
        return page;
    }

    private static void addSetupRow(JPanel panel, GridBagConstraints constraints, int row, String label, Component component) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.weighty = 0.0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel(label + ":"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(component, constraints);
    }

    private void updateSetupOptionState() {
        String preset = String.valueOf(this.setupPreset.getSelectedItem());
        this.setupPlatform.setEnabled(preset.equals("void"));
        this.setupGeneratorSettings.setEnabled(preset.equals("flat"));
        if (preset.equals("void")) {
            this.setupStructures.setSelected(false);
        }
    }

    private void runSetupOperation(String operation) {
        String worldName = this.setupWorldName.getText().strip();
        if (worldName.isEmpty()) {
            JOptionPane.showMessageDialog(this.frame, "Enter a world name first.", "PaperLive Dev Tool", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (operation.equals("reset")) {
            int confirmation = JOptionPane.showConfirmDialog(
                this.frame,
                "Reset world '" + worldName + "'?\nPlayers will be moved and the existing world will be backed up first.",
                "Confirm world reset",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (confirmation != JOptionPane.OK_OPTION) {
                return;
            }
        }

        List<String> arguments = new ArrayList<>();
        arguments.add("world");
        arguments.add(operation);
        arguments.add(worldName);
        arguments.add(String.valueOf(this.setupPreset.getSelectedItem()));
        String seed = this.setupSeed.getText().strip();
        arguments.add("seed=" + (seed.isEmpty() ? "random" : seed));
        arguments.add("environment=" + this.setupEnvironment.getSelectedItem());
        arguments.add("structures=" + this.setupStructures.isSelected());
        arguments.add("hardcore=" + this.setupHardcore.isSelected());
        arguments.add("bonus-chest=" + this.setupBonusChest.isSelected());
        arguments.add("platform=" + this.setupPlatform.isSelected());
        String settings = this.setupGeneratorSettings.getText().strip();
        if (!settings.isEmpty()) {
            arguments.add("settings=" + settings);
        }
        if (operation.equals("reset")) {
            arguments.add("confirm");
        }

        this.setSetupActionsEnabled(false);
        this.appendSetupOutput("Starting " + operation + " for '" + worldName + "'...");
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            this.appendSetupOutput("PaperLive server is not ready.");
            this.setSetupActionsEnabled(true);
            return;
        }
        minecraftServer.execute(() -> {
            try {
                PaperLiveWorldManager.execute(this::appendSetupOutput, arguments.toArray(String[]::new));
            } catch (Throwable throwable) {
                this.appendSetupOutput("Operation failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    this.setSetupActionsEnabled(true);
                    this.refreshSetupWorlds();
                });
            }
        });
    }

    private void setSetupActionsEnabled(boolean enabled) {
        this.setupCreateButton.setEnabled(enabled);
        this.setupResetButton.setEnabled(enabled);
        this.setupTeleportButton.setEnabled(enabled);
    }

    private void runSnapshotOperation(boolean restore) {
        String snapshotName = this.setupSnapshotName.getText().strip();
        String worldName = this.setupWorldName.getText().strip();
        if (snapshotName.isEmpty() || worldName.isEmpty()) {
            JOptionPane.showMessageDialog(this.frame, "Enter both a snapshot name and loaded world name first.", "PaperLive Dev Tool", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (restore) {
            int answer = JOptionPane.showConfirmDialog(
                this.frame,
                "Restore snapshot '" + snapshotName + "' into world '" + worldName + "'?\nCurrent world and source-plugin data will be moved to a recovery directory first.",
                "Confirm dev snapshot restore",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            this.appendSetupOutput("PaperLive server is not ready.");
            return;
        }
        this.appendSetupOutput((restore ? "Restoring" : "Saving") + " snapshot '" + snapshotName + "' for world '" + worldName + "'...");
        minecraftServer.execute(() -> {
            try {
                if (restore) {
                    PaperLiveDevSnapshots.restore(this::appendSetupOutput, snapshotName, worldName);
                } else {
                    PaperLiveDevSnapshots.create(this::appendSetupOutput, snapshotName, worldName);
                }
            } catch (Throwable throwable) {
                this.appendSetupOutput("Snapshot failed: " + throwable.getMessage());
            } finally {
                this.refreshSetupWorlds();
                this.refreshPlugins();
            }
        });
    }

    private void teleportSetupPlayer() {
        String worldName = this.setupWorldName.getText().strip();
        String playerName = String.valueOf(this.setupPlayer.getSelectedItem());
        if (worldName.isEmpty() || playerName.isBlank() || playerName.equals("null")) {
            JOptionPane.showMessageDialog(this.frame, "Select a loaded world and an online player first.", "PaperLive Dev Tool", JOptionPane.WARNING_MESSAGE);
            return;
        }
        this.setSetupActionsEnabled(false);
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            this.appendSetupOutput("PaperLive server is not ready.");
            this.setSetupActionsEnabled(true);
            return;
        }
        minecraftServer.execute(() -> {
            try {
                org.bukkit.World world = this.server.getWorld(worldName);
                org.bukkit.entity.Player player = this.server.getPlayerExact(playerName);
                if (world == null) {
                    this.appendSetupOutput("World '" + worldName + "' is not loaded.");
                } else if (player == null) {
                    this.appendSetupOutput("Player '" + playerName + "' is no longer online.");
                } else {
                    org.bukkit.Location spawn = world.getSpawnLocation().add(0.5, 0.0, 0.5);
                    if (player.teleport(spawn)) {
                        this.appendSetupOutput("Teleported " + player.getName() + " to " + world.getName() + " spawn at "
                            + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", spawn.getX(), spawn.getY(), spawn.getZ()) + '.');
                    } else {
                        this.appendSetupOutput("Teleport was cancelled or rejected.");
                    }
                }
            } catch (Throwable throwable) {
                this.appendSetupOutput("Teleport failed: " + throwable.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> this.setSetupActionsEnabled(true));
            }
        });
    }

    private void appendSetupOutput(String message) {
        SwingUtilities.invokeLater(() -> {
            this.setupOutput.append(TIME_FORMAT.format(Instant.now()) + "  " + message.replaceAll("§.", "") + '\n');
            this.setupOutput.setCaretPosition(this.setupOutput.getDocument().getLength());
        });
    }

    private void refreshSetupWorlds() {
        if (!this.setupRefreshPending.compareAndSet(false, true)) {
            return;
        }
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        if (minecraftServer == null) {
            this.setupRefreshPending.set(false);
            return;
        }
        minecraftServer.execute(() -> {
            List<SetupWorldSnapshot> snapshots = new ArrayList<>();
            List<String> onlinePlayers = new ArrayList<>();
            try {
                List<org.bukkit.World> worlds = this.server.getWorlds();
                org.bukkit.World primary = worlds.isEmpty() ? null : worlds.getFirst();
                for (org.bukkit.World world : worlds) {
                    snapshots.add(new SetupWorldSnapshot(
                        world.getName(),
                        PaperLiveWorldManager.presetFor(world),
                        world.getEnvironment().name(),
                        world.getSeed(),
                        world.getPlayers().size(),
                        world == primary
                    ));
                }
                onlinePlayers.addAll(this.server.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
            } catch (Throwable throwable) {
                this.appendSetupOutput("Could not refresh worlds: " + throwable.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    String selectedWorld = tableRowKey(this.setupWorldTable, 0);
                    this.setupWorldModel.setRowCount(0);
                    for (SetupWorldSnapshot snapshot : snapshots) {
                        this.setupWorldModel.addRow(new Object[]{
                            snapshot.name(), snapshot.type(), snapshot.environment(), snapshot.seed(), snapshot.players(), snapshot.primary()
                        });
                    }
                    restoreTableSelection(this.setupWorldTable, selectedWorld, 0);
                    String selectedPlayer = String.valueOf(this.setupPlayer.getSelectedItem());
                    this.setupPlayer.removeAllItems();
                    for (String player : onlinePlayers) {
                        this.setupPlayer.addItem(player);
                    }
                    if (!selectedPlayer.equals("null") && onlinePlayers.contains(selectedPlayer)) {
                        this.setupPlayer.setSelectedItem(selectedPlayer);
                    }
                    String selectedScenarioPlayer = String.valueOf(this.scenarioPlayer.getSelectedItem());
                    this.scenarioPlayer.removeAllItems();
                    for (String player : onlinePlayers) {
                        this.scenarioPlayer.addItem(player);
                    }
                    if (!selectedScenarioPlayer.equals("null") && onlinePlayers.contains(selectedScenarioPlayer)) {
                        this.scenarioPlayer.setSelectedItem(selectedScenarioPlayer);
                    }
                } finally {
                    this.setupRefreshPending.set(false);
                }
            });
        });
    }

    private record SetupWorldSnapshot(String name, String type, String environment, long seed, int players, boolean primary) {
    }

    private void receiveException(PaperLiveDebugRecord record) {
        if (this.closed.get()) {
            return;
        }
        synchronized (this.pendingRecords) {
            while (this.pendingRecords.size() >= MAX_WINDOW_RECORDS) {
                this.pendingRecords.removeFirst();
            }
            this.pendingRecords.addLast(record);
        }
    }

    private void drainPendingRecords() {
        List<PaperLiveDebugRecord> records;
        synchronized (this.pendingRecords) {
            if (this.pendingRecords.isEmpty()) {
                return;
            }
            records = List.copyOf(this.pendingRecords);
            this.pendingRecords.clear();
        }

        boolean followNewest = this.recordList.getSelectedIndex() < 0
            || this.recordList.getSelectedIndex() == this.visibleRecords.size() - 1;
        for (PaperLiveDebugRecord record : records) {
            this.addRecord(record, false);
        }
        if (followNewest && !this.pauseButton.isSelected() && !this.visibleRecords.isEmpty()) {
            int newest = this.visibleRecords.size() - 1;
            this.recordList.setSelectedIndex(newest);
            this.recordList.ensureIndexIsVisible(newest);
        }
    }

    private void addRecord(PaperLiveDebugRecord record) {
        this.addRecord(record, true);
    }

    private void addRecord(PaperLiveDebugRecord record, boolean selectNewest) {
        if (!this.knownSequences.add(record.sequence())) {
            return;
        }
        if (this.allRecords.size() >= MAX_WINDOW_RECORDS) {
            PaperLiveDebugRecord removed = this.allRecords.removeFirst();
            this.knownSequences.remove(removed.sequence());
            this.visibleRecords.removeElement(removed);
        }
        this.allRecords.add(record);
        if (this.pauseButton.isSelected()) {
            this.pausedRecordCount++;
        } else if (this.matchesFilter(record)) {
            this.visibleRecords.addElement(record);
            if (selectNewest) {
                this.recordList.setSelectedIndex(this.visibleRecords.size() - 1);
                this.recordList.ensureIndexIsVisible(this.visibleRecords.size() - 1);
            }
        }
        this.refreshStatus();
    }

    private void refreshVisibleExceptions() {
        PaperLiveDebugRecord selected = this.recordList.getSelectedValue();
        this.visibleRecords.clear();
        for (PaperLiveDebugRecord record : this.allRecords) {
            if (this.matchesFilter(record)) {
                this.visibleRecords.addElement(record);
            }
        }
        if (selected != null) {
            this.recordList.setSelectedValue(selected, true);
        }
        if (this.recordList.getSelectedIndex() < 0 && !this.visibleRecords.isEmpty()) {
            this.recordList.setSelectedIndex(this.visibleRecords.size() - 1);
        }
        this.refreshStatus();
    }

    private boolean matchesFilter(PaperLiveDebugRecord record) {
        String filter = this.filterField.getText().strip().toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) {
            return true;
        }
        return searchableText(record).contains(filter);
    }

    private void refreshStatus() {
        String dropped = this.debugger.droppedEventTraceCount() == 0 ? "" : " • " + this.debugger.droppedEventTraceCount() + " traces rate-limited";
        if (this.pauseButton.isSelected()) {
            this.statusLabel.setText("Paused • " + this.pausedRecordCount + " new • " + this.allRecords.size() + " captured" + dropped);
            this.statusLabel.setForeground(new Color(176, 106, 0));
        } else {
            this.statusLabel.setText("● Live • in-process • " + this.allRecords.size() + " captured" + dropped);
            this.statusLabel.setForeground(new Color(20, 130, 70));
        }
    }

    private void showDetails(@Nullable PaperLiveDebugRecord record) {
        this.handlerModel.setRowCount(0);
        if (record == null) {
            this.overview.setText("Waiting for a PaperLive diagnostic…");
            this.stackTrace.setText("");
            this.refreshRegisteredHandlers();
            return;
        }

        if (record instanceof PaperLiveBuildFailure failure) {
            this.overview.setText(buildFailureOverview(failure));
            this.overview.setCaretPosition(0);
            this.stackTrace.setText(failure.output());
            this.stackTrace.setCaretPosition(0);
            this.refreshRegisteredHandlers();
            return;
        }

        if (record instanceof PaperLiveCommandTrace commandTrace) {
            this.overview.setText(commandOverview(commandTrace));
            this.overview.setCaretPosition(0);
            this.stackTrace.setText(this.commandTimelineText(commandTrace));
            this.stackTrace.setCaretPosition(0);
            this.refreshRegisteredHandlers();
            return;
        }

        if (record instanceof PaperLiveEventTrace eventTrace) {
            this.overview.setText(eventTraceOverview(eventTrace));
            this.overview.setCaretPosition(0);
            for (PaperLiveEventTrace.HandlerExecution execution : eventTrace.handlers()) {
                PaperLiveDebugException.HandlerSnapshot handler = execution.handler();
                this.handlerModel.addRow(new Object[]{
                    execution.index() + 1,
                    eventTrace.eventBefore().name(),
                    handler.plugin().name(),
                    handler.plugin().version(),
                    handler.priority(),
                    simpleName(handler.listenerClass()),
                    handler.plugin().enabled(),
                    handler.ignoreCancelled(),
                    formatDuration(execution.durationNanos()),
                    cancellationChange(execution.cancelledBefore(), execution.cancelledAfter()),
                    execution.status()
                });
            }
            this.stackTrace.setText(eventTraceTimeline(eventTrace));
            this.stackTrace.setCaretPosition(0);
            return;
        }

        PaperLiveDebugException snapshot = (PaperLiveDebugException) record;
        this.overview.setText(overviewText(snapshot));
        this.overview.setCaretPosition(0);
        for (PaperLiveDebugException.HandlerSnapshot handler : snapshot.handlers()) {
            this.handlerModel.addRow(new Object[]{
                handler.index() + 1,
                snapshot.event().name(),
                handler.plugin().name(),
                handler.plugin().version(),
                handler.priority(),
                simpleName(handler.listenerClass()),
                handler.plugin().enabled(),
                handler.ignoreCancelled(),
                "",
                snapshot.event().cancelled() == null ? "n/a" : snapshot.event().cancelled(),
                handler.index() == snapshot.failingHandlerIndex() ? "FAILED" : ""
            });
        }
        this.stackTrace.setText(stackTraceText(snapshot.throwable()));
        this.stackTrace.setCaretPosition(0);
    }

    private void refreshRegisteredHandlers() {
        List<PaperLiveHandlerRegistration> registrations = this.debugger.registeredHandlers().stream()
            .sorted(Comparator.comparing(PaperLiveHandlerRegistration::eventName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(PaperLiveHandlerRegistration::prioritySlot)
                .thenComparingLong(PaperLiveHandlerRegistration::registrationSequence))
            .toList();
        this.refreshSelectorChoices(
            this.traceEventSelector,
            this.traceEventModel,
            this.knownEventChoices,
            registrations.stream().map(PaperLiveHandlerRegistration::eventName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()
        );
        this.refreshSelectorChoices(
            this.tracePluginSelector,
            this.tracePluginModel,
            this.knownPluginChoices,
            registrations.stream().map(PaperLiveHandlerRegistration::pluginName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()
        );
        this.refreshSelectorChoices(
            this.tracePlayerSelector,
            this.tracePlayerModel,
            this.knownPlayerChoices,
            this.debugger.knownPlayerNames().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()
        );
        if (this.recordList.getSelectedValue() instanceof PaperLiveDebugException || this.recordList.getSelectedValue() instanceof PaperLiveEventTrace) {
            return;
        }
        String selectedHandler = tableRowKey(this.handlerTable, 1, 2, 5);
        this.handlerModel.setRowCount(0);
        int index = 1;
        for (PaperLiveHandlerRegistration registration : registrations) {
            this.handlerModel.addRow(new Object[]{
                index++,
                registration.eventName(),
                registration.pluginName(),
                registration.pluginVersion(),
                registration.priority(),
                simpleName(registration.listenerClass()),
                registration.pluginEnabled(),
                registration.ignoreCancelled(),
                "",
                "",
                ""
            });
        }
        restoreTableSelection(this.handlerTable, selectedHandler, 1, 2, 5);
    }

    private void refreshCommandTimeline() {
        if (!(this.recordList.getSelectedValue() instanceof PaperLiveCommandTrace trace)) {
            return;
        }
        String updated = this.commandTimelineText(trace);
        if (!updated.equals(this.stackTrace.getText())) {
            int caret = this.stackTrace.getCaretPosition();
            this.stackTrace.setText(updated);
            this.stackTrace.setCaretPosition(Math.min(caret, updated.length()));
        }
    }

    private void updateEventTraceFilter() {
        if (this.updatingSelectorModels) {
            return;
        }
        this.debugger.updateEventTraceFilter(
            this.traceEvents.isSelected(),
            String.valueOf(this.traceEventSelector.getEditor().getItem()),
            String.valueOf(this.tracePluginSelector.getEditor().getItem()),
            String.valueOf(this.tracePlayerSelector.getEditor().getItem())
        );
    }

    private static void configureTraceSelector(JComboBox<String> selector, int width, String tooltip) {
        selector.setMaximumSize(new Dimension(width, 28));
        selector.setPreferredSize(new Dimension(width, 28));
        selector.setToolTipText(tooltip);
    }

    private void addEventChoice(String eventName) {
        addSelectorChoice(this.traceEventModel, this.knownEventChoices, eventName);
    }

    private void addPluginChoice(String pluginName) {
        addSelectorChoice(this.tracePluginModel, this.knownPluginChoices, pluginName);
    }

    private void addPlayerChoice(String playerName) {
        addSelectorChoice(this.tracePlayerModel, this.knownPlayerChoices, playerName);
    }

    private static void addSelectorChoice(DefaultComboBoxModel<String> model, Set<String> knownChoices, String value) {
        if (!value.isBlank() && !value.contains(",") && knownChoices.add(value.toLowerCase(Locale.ROOT))) {
            model.addElement(value);
        }
    }

    private void refreshSelectorChoices(
        JComboBox<String> selector,
        DefaultComboBoxModel<String> model,
        Set<String> knownChoices,
        List<String> choices
    ) {
        String editorValue = String.valueOf(selector.getEditor().getItem());
        Set<String> desired = choices.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (!editorValue.isBlank() && !editorValue.contains(",")) {
            desired.add(editorValue.toLowerCase(Locale.ROOT));
        }
        this.updatingSelectorModels = true;
        try {
            for (int index = model.getSize() - 1; index >= 0; index--) {
                String existing = model.getElementAt(index);
                if (!desired.contains(existing.toLowerCase(Locale.ROOT))) {
                    model.removeElementAt(index);
                    knownChoices.remove(existing.toLowerCase(Locale.ROOT));
                }
            }
            for (String choice : choices) {
                addSelectorChoice(model, knownChoices, choice);
            }
            selector.getEditor().setItem(editorValue);
        } finally {
            this.updatingSelectorModels = false;
        }
    }

    private void configureTraceSelectorUpdates(JComboBox<String> selector) {
        selector.addActionListener(event -> this.updateEventTraceFilter());
        if (selector.getEditor().getEditorComponent() instanceof JTextField editor) {
            addDocumentChangeListener(editor, this::updateEventTraceFilter);
        }
    }

    private static void addDocumentChangeListener(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        });
    }

    private String commandTimelineText(PaperLiveCommandTrace trace) {
        StringBuilder text = new StringBuilder();
        text.append(trace.completedNormally() ? "✓ " : "✗ ")
            .append('/').append(trace.commandLine())
            .append("  [").append(formatDuration(trace.durationNanos())).append("]\n");
        List<PaperLiveActivitySpan> spans = this.debugger.activitySpans(trace.traceId()).stream()
            .sorted(Comparator.comparingLong(PaperLiveActivitySpan::startedAtEpochMillis))
            .toList();
        appendChildSpans(text, spans, trace.rootSpanId(), "");
        if (spans.isEmpty()) {
            text.append("\nNo correlated background activity has completed yet.");
        }
        if (trace.throwable() != null) {
            text.append("\n\nCommand exception\n-----------------\n");
            appendThrowable(text, trace.throwable(), false);
        }
        return text.toString();
    }

    private static void appendChildSpans(StringBuilder text, List<PaperLiveActivitySpan> spans, long parentSpanId, String indent) {
        List<PaperLiveActivitySpan> children = spans.stream().filter(span -> span.parentSpanId() == parentSpanId).toList();
        for (int index = 0; index < children.size(); index++) {
            PaperLiveActivitySpan span = children.get(index);
            boolean last = index == children.size() - 1;
            text.append(indent).append(last ? "└─ " : "├─ ")
                .append(span.successful() ? "✓ " : "✗ ")
                .append(span.name())
                .append("  [").append(formatDuration(span.durationNanos())).append("]")
                .append("  on ").append(span.threadName()).append('\n');
            for (Map.Entry<String, String> detail : span.details().entrySet()) {
                text.append(indent).append(last ? "   " : "│  ").append("   ")
                    .append(detail.getKey()).append(": ").append(detail.getValue()).append('\n');
            }
            if (span.failureReason() != null) {
                text.append(indent).append(last ? "   " : "│  ").append("   reason: ").append(span.failureReason()).append('\n');
            }
            appendChildSpans(text, spans, span.spanId(), indent + (last ? "   " : "│  "));
        }
    }

    private void startLifecycleWatcher() {
        Thread.ofPlatform().name("PaperLive Dev Tool Lifecycle").daemon(true).start(() -> {
            while (!this.closed.get() && !this.server.isStopping()) {
                LockSupport.parkNanos(500_000_000L);
            }
            this.close();
        });
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(MONOSPACED);
        area.setMargin(new java.awt.Insets(10, 10, 10, 10));
        return area;
    }

    private static String overviewText(PaperLiveDebugException snapshot) {
        StringBuilder text = new StringBuilder();
        text.append("Time:              ").append(TIME_FORMAT.format(Instant.ofEpochMilli(snapshot.occurredAtEpochMillis()))).append('\n');
        text.append("Event:             ").append(snapshot.event().name()).append('\n');
        text.append("Event class:       ").append(snapshot.event().className()).append('\n');
        text.append("Thread:            ").append(snapshot.event().threadName()).append('\n');
        text.append("Asynchronous:      ").append(snapshot.event().asynchronous()).append('\n');
        text.append("Cancelled:         ").append(snapshot.event().cancelled() == null ? "n/a" : snapshot.event().cancelled()).append('\n');
        text.append("Failing plugin:    ").append(snapshot.failingPlugin().name()).append(" ").append(snapshot.failingPlugin().version()).append('\n');
        text.append("Plugin enabled:    ").append(snapshot.failingPlugin().enabled()).append('\n');
        text.append("Listener:          ").append(snapshot.listenerClass()).append('\n');
        text.append("Previous handlers: ").append(snapshot.previousHandlerCount()).append('\n');
        text.append("Exception:         ").append(snapshot.throwable().type());
        if (snapshot.throwable().message() != null) {
            text.append(": ").append(snapshot.throwable().message());
        }
        text.append("\n\nEvent context\n-------------\n");
        if (snapshot.event().context().isEmpty()) {
            text.append("No specialized context available.");
        } else {
            snapshot.event().context().forEach((key, value) -> text.append(String.format(Locale.ROOT, "%-20s %s%n", key + ":", value)));
        }
        return text.toString();
    }

    private static String eventTraceOverview(PaperLiveEventTrace trace) {
        PaperLiveDebugException.EventSnapshot before = trace.eventBefore();
        PaperLiveDebugException.EventSnapshot after = trace.eventAfter();
        StringBuilder text = new StringBuilder();
        text.append("Time:          ").append(TIME_FORMAT.format(Instant.ofEpochMilli(trace.occurredAtEpochMillis()))).append('\n');
        text.append("Type:          Successful event trace\n");
        text.append("Event:         ").append(before.name()).append('\n');
        text.append("Event class:   ").append(before.className()).append('\n');
        text.append("Thread:        ").append(before.threadName()).append('\n');
        text.append("Asynchronous:  ").append(before.asynchronous()).append('\n');
        text.append("Duration:      ").append(formatDuration(trace.durationNanos())).append('\n');
        text.append("Handlers:      ").append(trace.handlers().size()).append('\n');
        text.append("Cancelled:     ").append(cancellationChange(before.cancelled(), after.cancelled())).append('\n');
        if (trace.traceId() != 0) {
            text.append("Command trace: ").append(trace.traceId()).append('\n');
        }
        text.append("\nEvent context before\n--------------------\n");
        appendContext(text, before.context());
        if (!before.context().equals(after.context())) {
            text.append("\nEvent context after\n-------------------\n");
            appendContext(text, after.context());
        }
        return text.toString();
    }

    private static String eventTraceTimeline(PaperLiveEventTrace trace) {
        StringBuilder text = new StringBuilder();
        text.append(trace.eventBefore().name()).append("  [").append(formatDuration(trace.durationNanos())).append("]\n");
        if (trace.handlers().isEmpty()) {
            text.append("\nNo registered handlers were dispatched.");
            return text.toString();
        }
        for (int index = 0; index < trace.handlers().size(); index++) {
            PaperLiveEventTrace.HandlerExecution execution = trace.handlers().get(index);
            boolean last = index == trace.handlers().size() - 1;
            text.append(last ? "└─ " : "├─ ")
                .append(execution.index() + 1).append(". ")
                .append(execution.handler().plugin().name()).append(" · ")
                .append(simpleName(execution.handler().listenerClass())).append(" · ")
                .append(execution.handler().priority()).append("  [")
                .append(formatDuration(execution.durationNanos())).append("]  ")
                .append(execution.status());
            String cancellation = cancellationChange(execution.cancelledBefore(), execution.cancelledAfter());
            if (!"n/a".equals(cancellation)) {
                text.append("  cancelled: ").append(cancellation);
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static void appendContext(StringBuilder text, Map<String, String> context) {
        if (context.isEmpty()) {
            text.append("No specialized context available.\n");
        } else {
            context.forEach((key, value) -> text.append(String.format(Locale.ROOT, "%-20s %s%n", key + ":", value)));
        }
    }

    private static String cancellationChange(@Nullable Boolean before, @Nullable Boolean after) {
        if (before == null && after == null) {
            return "n/a";
        }
        if (java.util.Objects.equals(before, after)) {
            return String.valueOf(after);
        }
        return String.valueOf(before) + " → " + after;
    }

    private static String buildFailureOverview(PaperLiveBuildFailure failure) {
        StringBuilder text = new StringBuilder();
        text.append("Time:         ").append(TIME_FORMAT.format(Instant.ofEpochMilli(failure.occurredAtEpochMillis()))).append('\n');
        text.append("Type:         Build failure\n");
        text.append("Project:      ").append(failure.projectName()).append('\n');
        text.append("Build system: ").append(failure.buildSystem()).append('\n');
        text.append("Command:      ").append(failure.command()).append('\n');
        text.append("Exit code:    ").append(failure.exitCode() == null ? "n/a" : failure.exitCode()).append('\n');
        text.append("Reason:       ").append(failure.reason()).append('\n');
        text.append("Build log:    ").append(failure.logFile()).append('\n');
        text.append("\nOpen the ‘Output / stack trace’ tab for the compiler output.");
        return text.toString();
    }

    private static String commandOverview(PaperLiveCommandTrace trace) {
        StringBuilder text = new StringBuilder();
        text.append("Time:            ").append(TIME_FORMAT.format(Instant.ofEpochMilli(trace.occurredAtEpochMillis()))).append('\n');
        text.append("Type:            Command trace\n");
        text.append("Command:         /").append(trace.commandLine()).append('\n');
        text.append("Label:           ").append(trace.commandLabel()).append('\n');
        text.append("Sender:          ").append(trace.senderName()).append('\n');
        text.append("Sender type:     ").append(trace.senderType()).append('\n');
        if (trace.playerUuid() != null) {
            text.append("Player UUID:     ").append(trace.playerUuid()).append('\n');
        }
        if (trace.world() != null) {
            text.append("World:           ").append(trace.world()).append('\n');
        }
        text.append("Owner:           ").append(trace.ownerName());
        if (!trace.ownerVersion().isEmpty()) {
            text.append(' ').append(trace.ownerVersion());
        }
        text.append('\n');
        text.append("Command class:   ").append(trace.commandClass()).append('\n');
        text.append("Thread:          ").append(trace.threadName()).append('\n');
        text.append("Duration:        ").append(formatDuration(trace.durationNanos())).append('\n');
        text.append("Executor result: ").append(trace.executorResult()).append('\n');
        text.append("Completed:       ").append(trace.completedNormally()).append('\n');
        if (trace.throwable() != null) {
            text.append("Exception:       ").append(trace.throwable().type());
            if (trace.throwable().message() != null) {
                text.append(": ").append(trace.throwable().message());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String stackTraceText(PaperLiveDebugException.ThrowableSnapshot throwable) {
        StringBuilder text = new StringBuilder();
        appendThrowable(text, throwable, false);
        return text.toString();
    }

    private static void appendThrowable(StringBuilder text, PaperLiveDebugException.ThrowableSnapshot throwable, boolean cause) {
        if (cause) {
            text.append("Caused by: ");
        }
        text.append(throwable.type());
        if (throwable.message() != null) {
            text.append(": ").append(throwable.message());
        }
        text.append('\n');
        for (PaperLiveDebugException.StackFrameSnapshot frame : throwable.stackTrace()) {
            text.append("\tat ").append(frame.className()).append('.').append(frame.methodName()).append('(');
            if (frame.fileName() == null) {
                text.append("Unknown Source");
            } else {
                text.append(frame.fileName());
                if (frame.lineNumber() >= 0) {
                    text.append(':').append(frame.lineNumber());
                }
            }
            text.append(")\n");
        }
        if (throwable.cause() != null) {
            appendThrowable(text, throwable.cause(), true);
        }
    }

    private static String searchableText(PaperLiveDebugRecord record) {
        if (record instanceof PaperLiveBuildFailure failure) {
            return (failure.projectName() + ' ' + failure.buildSystem() + ' ' + failure.command() + ' '
                + failure.reason() + ' ' + failure.logFile() + ' ' + failure.output()).toLowerCase(Locale.ROOT);
        }
        if (record instanceof PaperLiveCommandTrace trace) {
            return (trace.commandLine() + ' ' + trace.commandLabel() + ' ' + trace.senderName() + ' '
                + trace.senderType() + ' ' + trace.ownerName() + ' ' + trace.commandClass() + ' '
                + (trace.throwable() == null ? "" : trace.throwable().type() + ' ' + trace.throwable().message())).toLowerCase(Locale.ROOT);
        }
        if (record instanceof PaperLiveEventTrace trace) {
            StringBuilder text = new StringBuilder()
                .append(trace.eventBefore().name()).append(' ')
                .append(trace.eventBefore().className()).append(' ');
            trace.eventBefore().context().forEach((key, value) -> text.append(key).append(' ').append(value).append(' '));
            trace.handlers().forEach(execution -> text.append(execution.handler().plugin().name()).append(' ')
                .append(execution.handler().listenerClass()).append(' ').append(execution.status()).append(' '));
            return text.toString().toLowerCase(Locale.ROOT);
        }
        PaperLiveDebugException snapshot = (PaperLiveDebugException) record;
        StringBuilder text = new StringBuilder()
            .append(snapshot.event().name()).append(' ')
            .append(snapshot.event().className()).append(' ')
            .append(snapshot.failingPlugin().name()).append(' ')
            .append(snapshot.listenerClass()).append(' ')
            .append(snapshot.throwable().type()).append(' ')
            .append(snapshot.throwable().message()).append(' ');
        snapshot.event().context().forEach((key, value) -> text.append(key).append(' ').append(value).append(' '));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static String formatDuration(long durationNanos) {
        if (durationNanos < 1_000_000L) {
            return String.format(Locale.ROOT, "%.3f ms", durationNanos / 1_000_000.0D);
        }
        return String.format(Locale.ROOT, "%.2f ms", durationNanos / 1_000_000.0D);
    }

    private static @Nullable BufferedImage loadIcon() {
        try (InputStream input = PaperLiveDebuggerWindow.class.getClassLoader().getResourceAsStream("logo.png")) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static final class RecordRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            Component component = super.getListCellRendererComponent(list, value, index, selected, focused);
            if (component instanceof JLabel label && value instanceof PaperLiveCommandTrace trace) {
                String status = trace.completedNormally() ? "Command" : "Command failed";
                label.setText(status + "  ·  /" + trace.commandLine() + "  ·  "
                    + TIME_FORMAT.format(Instant.ofEpochMilli(trace.occurredAtEpochMillis())) + "  ·  "
                    + trace.ownerName() + "  ·  " + formatDuration(trace.durationNanos()));
            } else if (component instanceof JLabel label && value instanceof PaperLiveBuildFailure failure) {
                label.setText("Build failed  ·  " + failure.projectName() + "  ·  "
                    + TIME_FORMAT.format(Instant.ofEpochMilli(failure.occurredAtEpochMillis())) + "  ·  " + failure.reason());
            } else if (component instanceof JLabel label && value instanceof PaperLiveEventTrace trace) {
                String player = trace.eventBefore().context().getOrDefault("player", "");
                label.setText("Event  ·  " + trace.eventBefore().name()
                    + (player.isEmpty() ? "" : "  ·  " + player) + "  ·  "
                    + TIME_FORMAT.format(Instant.ofEpochMilli(trace.occurredAtEpochMillis())) + "  ·  "
                    + trace.handlers().size() + " handlers  ·  " + formatDuration(trace.durationNanos()));
            } else if (component instanceof JLabel label && value instanceof PaperLiveDebugException snapshot) {
                String message = snapshot.throwable().message() == null ? simpleName(snapshot.throwable().type()) : snapshot.throwable().message();
                label.setText(snapshot.event().name() + "  ·  " + snapshot.failingPlugin().name() + "  ·  "
                    + TIME_FORMAT.format(Instant.ofEpochMilli(snapshot.occurredAtEpochMillis())) + "  ·  " + message);
            }
            if (component instanceof JLabel label) {
                label.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            }
            return component;
        }
    }
}
