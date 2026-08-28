package io.papermc.paper.plugin.debug;

import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("Normal")
class PaperLiveDebuggerTest {

    @Test
    void capturesPluginTaskTimingAndFailureWithoutRetainingThrowable() {
        PaperLiveDebugger debugger = new PaperLiveDebugger(10);
        Plugin plugin = plugin("Tasks", "2.0", true);
        IllegalStateException failure = new IllegalStateException("boom");

        debugger.captureTaskExecution(
            plugin,
            "42",
            "BUKKIT_ASYNC",
            PaperLiveDebuggerTest.class,
            "example.Tasks.schedule(Tasks.java:12)",
            true,
            true,
            75_000_000L,
            failure
        );

        PaperLiveTaskTrace trace = debugger.recentTaskTraces().getFirst();
        assertEquals("Tasks", trace.pluginName());
        assertEquals("42", trace.taskId());
        assertEquals(75_000_000L, trace.durationNanos());
        assertTrue(trace.asynchronous());
        assertTrue(trace.repeating());
        assertFalse(trace.successful());
        assertEquals("java.lang.IllegalStateException: boom", trace.failure());
    }

    @Test
    void capturesPlayerInteractionAndHandlerOrderWithoutRuntimeReferences() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");

        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Daan");
        when(player.getUniqueId()).thenReturn(UUID.fromString("c68c069f-582a-4853-af9a-f54d9a956334"));
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 12.5, 64.0, -7.25));

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(12);
        when(block.getY()).thenReturn(63);
        when(block.getZ()).thenReturn(-8);

        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND_SWORD);
        PlayerInteractEvent event = new PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            item,
            block,
            BlockFace.UP,
            EquipmentSlot.HAND
        );
        Plugin firstPlugin = plugin("AuditPlugin", "2.1.0", true);
        Plugin failingPlugin = plugin("RobberyPlugin", "1.0.0", true);
        RegisteredListener[] listeners = {
            listener(firstPlugin, EventPriority.LOW, false),
            listener(failingPlugin, EventPriority.HIGH, true)
        };
        NullPointerException failure = new NullPointerException("weapon configuration");

        PaperLiveDebugger debugger = new PaperLiveDebugger();
        debugger.captureEventException(event, listeners, 1, failure);

        PaperLiveDebugException snapshot = debugger.recentExceptions().getFirst();
        assertEquals(1, snapshot.sequence());
        assertEquals("PlayerInteractEvent", snapshot.event().name());
        assertEquals("Daan", snapshot.event().context().get("player"));
        assertEquals("survival", snapshot.event().context().get("world"));
        assertEquals("DIAMOND_SWORD", snapshot.event().context().get("item"));
        assertEquals("RIGHT_CLICK_BLOCK", snapshot.event().context().get("action"));
        assertEquals("CHEST", snapshot.event().context().get("clickedBlock"));
        assertFalse(snapshot.event().asynchronous());
        assertFalse(snapshot.event().cancelled());
        assertEquals(1, snapshot.previousHandlerCount());
        assertEquals(List.of("AuditPlugin", "RobberyPlugin"), snapshot.handlers().stream().map(handler -> handler.plugin().name()).toList());
        assertEquals("HIGH", snapshot.handlers().get(1).priority());
        assertTrue(snapshot.handlers().get(1).ignoreCancelled());
        assertEquals("RobberyPlugin", snapshot.failingPlugin().name());
        assertEquals(NullPointerException.class.getName(), snapshot.throwable().type());
        assertEquals("weapon configuration", snapshot.throwable().message());
        assertNull(snapshot.throwable().cause());
    }

    @Test
    void keepsOnlyTheConfiguredNumberOfRecentExceptions() {
        PaperLiveDebugger debugger = new PaperLiveDebugger(2);
        PlayerInteractEvent event = minimalEvent();
        RegisteredListener[] listeners = {listener(plugin("Example", "1.0", true), EventPriority.NORMAL, false)};

        debugger.captureEventException(event, listeners, 0, new IllegalStateException("first"));
        debugger.captureEventException(event, listeners, 0, new IllegalStateException("second"));
        debugger.captureEventException(event, listeners, 0, new IllegalStateException("third"));

        List<PaperLiveDebugException> snapshots = debugger.recentExceptions();
        assertEquals(2, snapshots.size());
        assertEquals(2, snapshots.get(0).sequence());
        assertEquals("second", snapshots.get(0).throwable().message());
        assertEquals(3, snapshots.get(1).sequence());
    }

    @Test
    void publishesLiveExceptionsUntilTheSubscriberCloses() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        PlayerInteractEvent event = minimalEvent();
        RegisteredListener[] listeners = {listener(plugin("Example", "1.0", true), EventPriority.NORMAL, false)};
        AtomicReference<PaperLiveDebugRecord> received = new AtomicReference<>();
        AtomicInteger deliveryCount = new AtomicInteger();
        PaperLiveDebugger.Subscription subscription = debugger.subscribe(snapshot -> {
            received.set(snapshot);
            deliveryCount.incrementAndGet();
        });

        debugger.captureEventException(event, listeners, 0, new IllegalStateException("first"));

        assertSame(debugger.recentExceptions().getFirst(), received.get());
        assertEquals(1, deliveryCount.get());

        subscription.close();
        debugger.captureEventException(event, listeners, 0, new IllegalStateException("second"));
        assertEquals(1, deliveryCount.get());
    }

    @Test
    void capturesBuildFailuresAsLiveDiagnostics() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();

        debugger.captureBuildFailure(
            "robberyplugin",
            "MAVEN",
            "mvnw.cmd package",
            1,
            "Build exited with code 1",
            "plugins/.paperlive-runtime/paperlive-robberyplugin.build.log",
            "WeaponListener.java:[76,23] cannot find symbol: sendActionBa"
        );

        PaperLiveBuildFailure failure = (PaperLiveBuildFailure) debugger.recentRecords().getFirst();
        assertEquals("robberyplugin", failure.projectName());
        assertEquals(1, failure.exitCode());
        assertTrue(failure.output().contains("sendActionBa"));
    }

    @Test
    void tracksAndRemovesLiveEventHandlerRegistrations() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        Plugin firstPlugin = plugin("FirstPlugin", "1.0", true);
        Plugin secondPlugin = plugin("SecondPlugin", "2.0", true);
        RegisteredListener first = listener(firstPlugin, EventPriority.LOW, false);
        RegisteredListener second = listener(secondPlugin, EventPriority.HIGH, true);

        debugger.recordEventRegistrations(PlayerInteractEvent.class, List.of(first, second));

        List<PaperLiveHandlerRegistration> registrations = debugger.registeredHandlers();
        assertEquals(2, registrations.size());
        assertEquals("PlayerInteractEvent", registrations.get(0).eventName());
        assertEquals("FirstPlugin", registrations.get(0).pluginName());
        assertEquals("LOW", registrations.get(0).priority());
        assertEquals("SecondPlugin", registrations.get(1).pluginName());

        debugger.removeEventRegistrations(firstPlugin);
        assertEquals(List.of("SecondPlugin"), debugger.registeredHandlers().stream().map(PaperLiveHandlerRegistration::pluginName).toList());
    }

    @Test
    void capturesSuccessfulPluginCommandsAndRedactsSensitiveArguments() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        Plugin plugin = plugin("RobberyPlugin", "1.0.0", true);
        Command command = new TestPluginCommand("robbery", plugin);
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("IDontLikeYourMom");

        debugger.captureCommand(sender, command, "robbery npc armor", "robbery", 2_500_000L, true, null);
        debugger.captureCommand(sender, new TestPluginCommand("login", plugin), "login secret-password", "login", 1_000L, true, null);

        PaperLiveCommandTrace trace = (PaperLiveCommandTrace) debugger.recentRecords().get(0);
        assertEquals("robbery npc armor", trace.commandLine());
        assertEquals("IDontLikeYourMom", trace.senderName());
        assertEquals("RobberyPlugin", trace.ownerName());
        assertEquals(2_500_000L, trace.durationNanos());
        assertTrue(trace.completedNormally());
        assertEquals("login <redacted>", ((PaperLiveCommandTrace) debugger.recentRecords().get(1)).commandLine());
    }

    @Test
    void propagatesCommandActivityAcrossAnAsynchronousBoundary() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        Plugin plugin = plugin("RobberyPlugin", "1.0.0", true);
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Daan");
        PaperLiveDebugger.CommandTraceScope command = debugger.beginCommand(
            sender,
            new TestPluginCommand("load", plugin),
            "load robberyplugin",
            "load"
        );
        PaperLiveDebugger.TraceContext context = debugger.captureTraceContext();
        Runnable backgroundWork = debugger.wrapAsync(context, "Build source project 'robberyplugin'", "BUILD", Map.of("project", "robberyplugin"), () -> {
            try (PaperLiveDebugger.ActivitySpanScope ignored = debugger.beginSpan("Prepare runtime JAR", "FILE", Map.of())) {
            }
        });

        command.complete(true, null);
        backgroundWork.run();

        PaperLiveCommandTrace trace = (PaperLiveCommandTrace) debugger.recentRecords().getFirst();
        List<PaperLiveActivitySpan> spans = debugger.activitySpans(trace.traceId());
        assertEquals(2, spans.size());
        PaperLiveActivitySpan inner = spans.stream().filter(span -> span.name().equals("Prepare runtime JAR")).findFirst().orElseThrow();
        PaperLiveActivitySpan outer = spans.stream().filter(span -> span.name().startsWith("Build source project")).findFirst().orElseThrow();
        assertEquals(trace.rootSpanId(), outer.parentSpanId());
        assertEquals(outer.spanId(), inner.parentSpanId());
    }

    @Test
    void tracesSuccessfulEventsWithFiltersHandlerDurationsAndCommandCorrelation() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        debugger.updateEventTraceFilter(true, "PlayerInteract", "Robbery", "Daan");
        PlayerInteractEvent event = minimalEvent("Daan");
        RegisteredListener listener = listener(plugin("RobberyPlugin", "1.0.0", true), EventPriority.HIGH, false);
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Daan");
        PaperLiveDebugger.CommandTraceScope command = debugger.beginCommand(
            sender,
            new TestPluginCommand("robbery", listener.getPlugin()),
            "robbery npc armor",
            "robbery"
        );

        PaperLiveDebugger.EventTraceSession session = debugger.beginEventTrace(event, new RegisteredListener[]{listener});
        assertNotNull(session);
        session.record(0, listener, "COMPLETED", 750_000L, false, true);
        session.complete();
        command.complete(true, null);

        PaperLiveEventTrace trace = (PaperLiveEventTrace) debugger.recentRecords().getFirst();
        PaperLiveCommandTrace commandTrace = (PaperLiveCommandTrace) debugger.recentRecords().get(1);
        assertEquals("PlayerInteractEvent", trace.eventBefore().name());
        assertEquals("Daan", trace.eventBefore().context().get("player"));
        assertEquals(commandTrace.traceId(), trace.traceId());
        assertEquals("RobberyPlugin", trace.handlers().getFirst().handler().plugin().name());
        assertEquals("COMPLETED", trace.handlers().getFirst().status());
        assertEquals(750_000L, trace.handlers().getFirst().durationNanos());
        assertFalse(trace.handlers().getFirst().cancelledBefore());
        assertTrue(trace.handlers().getFirst().cancelledAfter());
        assertTrue(debugger.activitySpans(trace.traceId()).stream().anyMatch(span -> span.category().equals("EVENT")));
    }

    @Test
    void doesNotTraceEventsOutsideTheConfiguredPlayerFilter() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        debugger.updateEventTraceFilter(true, "PlayerInteractEvent", "", "Daan");

        assertNull(debugger.beginEventTrace(minimalEvent("SomeoneElse"), new RegisteredListener[0]));
        assertTrue(debugger.recentRecords().isEmpty());
    }

    @Test
    void tracksPlayersForTheLiveSelectorUntilTheyQuit() {
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        PlayerInteractEvent interaction = minimalEvent("Daan");

        debugger.observeEvent(interaction);
        assertEquals(List.of("Daan"), debugger.knownPlayerNames());

        debugger.observeEvent(new PlayerQuitEvent(interaction.getPlayer(), (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertTrue(debugger.knownPlayerNames().isEmpty());
    }

    @Test
    void capturesCreatureTypeLocationAndSpawnReason() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");
        LivingEntity zombie = mock(LivingEntity.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        when(zombie.getUniqueId()).thenReturn(UUID.fromString("716723d3-57ea-4241-90f0-94482e49fadd"));
        when(zombie.getName()).thenReturn("Zombie");
        when(zombie.getWorld()).thenReturn(world);
        when(zombie.getLocation()).thenReturn(new Location(world, 120.5, 64.0, -32.5));
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);
        RegisteredListener listener = listener(plugin("SpawnMonitor", "1.0", true), EventPriority.NORMAL, false);
        PaperLiveDebugger debugger = new PaperLiveDebugger();
        debugger.updateEventTraceFilter(true, "CreatureSpawnEvent", "", "");

        PaperLiveDebugger.EventTraceSession session = debugger.beginEventTrace(event, new RegisteredListener[]{listener});
        assertNotNull(session);
        session.record(0, listener, "COMPLETED", 25_000L, false, false);
        session.complete();

        PaperLiveEventTrace trace = (PaperLiveEventTrace) debugger.recentRecords().getFirst();
        assertEquals("ZOMBIE", trace.eventBefore().context().get("entityType"));
        assertEquals("NATURAL", trace.eventBefore().context().get("spawnReason"));
        assertEquals("survival", trace.eventBefore().context().get("world"));
        assertEquals("120.5", trace.eventBefore().context().get("entityX"));
        assertEquals("64.0", trace.eventBefore().context().get("entityY"));
        assertEquals("-32.5", trace.eventBefore().context().get("entityZ"));
    }

    private static PlayerInteractEvent minimalEvent() {
        return minimalEvent("Player");
    }

    private static PlayerInteractEvent minimalEvent(String playerName) {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(playerName);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        return new PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    private static Plugin plugin(String name, String version, boolean enabled) {
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getName()).thenReturn(name);
        when(meta.getVersion()).thenReturn(version);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }

    private static RegisteredListener listener(Plugin plugin, EventPriority priority, boolean ignoreCancelled) {
        Listener listener = new Listener() {
        };
        EventExecutor executor = mock(EventExecutor.class);
        return new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled);
    }

    private static final class TestPluginCommand extends Command implements PluginIdentifiableCommand {

        private final Plugin plugin;

        private TestPluginCommand(String name, Plugin plugin) {
            super(name);
            this.plugin = plugin;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return true;
        }

        @Override
        public Plugin getPlugin() {
            return this.plugin;
        }
    }
}
