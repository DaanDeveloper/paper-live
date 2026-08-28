package io.papermc.paper.plugin.debug;

import org.jspecify.annotations.NullMarked;

/** Serializable description of one currently registered Bukkit event handler. */
@NullMarked
public record PaperLiveHandlerRegistration(
    long registrationSequence,
    String eventName,
    String eventClass,
    String pluginName,
    String pluginVersion,
    String listenerClass,
    String priority,
    int prioritySlot,
    boolean pluginEnabled,
    boolean ignoreCancelled
) {
}
