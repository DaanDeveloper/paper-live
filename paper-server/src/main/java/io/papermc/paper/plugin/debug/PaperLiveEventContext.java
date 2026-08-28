package io.papermc.paper.plugin.debug;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Extracts a small, side-effect-free set of useful values from known event types. */
@NullMarked
final class PaperLiveEventContext {

    private PaperLiveEventContext() {
    }

    static PaperLiveDebugException.EventSnapshot snapshot(Event event) {
        Map<String, String> context = new LinkedHashMap<>();

        if (event instanceof PlayerEvent playerEvent) {
            Player player = playerEvent.getPlayer();
            put(context, "player", player::getName);
            put(context, "playerUuid", () -> player.getUniqueId().toString());
            put(context, "world", () -> player.getWorld().getName());
            putLocation(context, "player", player::getLocation);
        }

        if (event instanceof EntityEvent entityEvent) {
            put(context, "entityType", () -> entityEvent.getEntityType().name());
            put(context, "entityUuid", () -> entityEvent.getEntity().getUniqueId().toString());
            put(context, "entityName", () -> entityEvent.getEntity().getName());
            put(context, "world", () -> entityEvent.getEntity().getWorld().getName());
            putLocation(context, "entity", entityEvent.getEntity()::getLocation);
        }

        if (event instanceof CreatureSpawnEvent spawnEvent) {
            put(context, "spawnReason", () -> spawnEvent.getSpawnReason().name());
        }

        if (event instanceof PlayerInteractEvent interactEvent) {
            put(context, "action", () -> interactEvent.getAction().name());
            put(context, "hand", () -> name(interactEvent.getHand()));
            put(context, "item", () -> itemType(interactEvent.getItem()));
            put(context, "blockFace", () -> interactEvent.getBlockFace().name());
            put(context, "useInteractedBlock", () -> interactEvent.useInteractedBlock().name());
            put(context, "useItemInHand", () -> interactEvent.useItemInHand().name());
            putBlock(context, interactEvent.getClickedBlock());
            putLocation(context, "interaction", interactEvent::getInteractionPoint);
        }

        Boolean cancelled = null;
        if (event instanceof Cancellable cancellable) {
            try {
                cancelled = cancellable.isCancelled();
            } catch (Throwable ignored) {
                context.put("cancelledCaptureError", "Unable to read cancellation state");
            }
        }

        return new PaperLiveDebugException.EventSnapshot(
            event.getEventName(),
            event.getClass().getName(),
            event.isAsynchronous(),
            Thread.currentThread().getName(),
            cancelled,
            context
        );
    }

    private static void putBlock(Map<String, String> context, @Nullable Block block) {
        if (block == null) {
            return;
        }
        put(context, "clickedBlock", () -> block.getType().name());
        put(context, "clickedBlockWorld", () -> block.getWorld().getName());
        put(context, "clickedBlockX", () -> Integer.toString(block.getX()));
        put(context, "clickedBlockY", () -> Integer.toString(block.getY()));
        put(context, "clickedBlockZ", () -> Integer.toString(block.getZ()));
    }

    private static void putLocation(Map<String, String> context, String prefix, Supplier<@Nullable Location> supplier) {
        try {
            Location location = supplier.get();
            if (location == null) {
                return;
            }
            context.put(prefix + "X", Double.toString(location.getX()));
            context.put(prefix + "Y", Double.toString(location.getY()));
            context.put(prefix + "Z", Double.toString(location.getZ()));
        } catch (Throwable ignored) {
            context.put(prefix + "LocationCaptureError", "Unable to read location");
        }
    }

    private static void put(Map<String, String> context, String key, Supplier<@Nullable String> supplier) {
        try {
            String value = supplier.get();
            if (value != null) {
                context.put(key, value);
            }
        } catch (Throwable ignored) {
            context.put(key + "CaptureError", "Unable to read value");
        }
    }

    private static @Nullable String name(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static @Nullable String itemType(@Nullable ItemStack item) {
        return item == null ? null : item.getType().name();
    }
}
