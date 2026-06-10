package io.github.term4.minestommechanics.tracking;

import io.github.term4.minestommechanics.util.TickClock;
import io.github.term4.minestommechanics.util.TickState;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerStartSprintingEvent;
import net.minestom.server.event.player.PlayerStopSprintingEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SprintTracker {

    private static final Tag<TickState> LAST_SPRINT_STATE = Tag.Transient("mm:last-sprint-state");
    private static final Tag<TickState> LAST_CLIENT_START_SPRINT = Tag.Transient("mm:client-sprint-start");
    private static final Tag<TickState> LAST_CLIENT_STOP_SPRINT  = Tag.Transient("mm:client-sprint-stop");

    public SprintTracker() { }

    /** Called when a player stops sprinting */
    public void markStopSprint(Player player) {
        player.setTag(LAST_SPRINT_STATE, new TickState(TickClock.now(), 0));
    }

    /** Listener node that stamps the sprint start/stop ticks. */
    public EventNode<@NotNull PlayerEvent> node() {
        EventNode<@NotNull PlayerEvent> node = EventNode.type("mm:sprint-tracker", EventFilter.PLAYER);

        node.addListener(PlayerStartSprintingEvent.class, e -> {
            Player p = e.getPlayer();
            p.setTag(LAST_CLIENT_START_SPRINT, new TickState(TickClock.now(), 0));
        });

        node.addListener(PlayerStopSprintingEvent.class, e -> {
            Player p = e.getPlayer();
            markStopSprint(p);
            p.setTag(LAST_CLIENT_STOP_SPRINT, new TickState(TickClock.now(), 0));
        });

        return node;
    }

    /** Returns true if a player was sprinting within {@code ticks}, returns raw sprint state if tracker doesn't exist */
    public static boolean wasRecentlySprinting(@Nullable SprintTracker t, Entity e, long ticks) {
        if (!(e instanceof Player p)) return e.isSprinting();
        if (t == null || p.isSprinting()) return p.isSprinting();
        TickState state = p.getTag(LAST_SPRINT_STATE);
        if (state == null) return false;
        return state.isActiveWithin((int) ticks);
    }

    /** True if the client's last sprint action was start (client thinks it is currently sprinting even if the server does not.) */
    public static boolean isClientSprinting(@Nullable SprintTracker t, Entity e) {
        if (t == null) return e.isSprinting();
        TickState start = e.getTag(LAST_CLIENT_START_SPRINT);
        TickState stop  = e.getTag(LAST_CLIENT_STOP_SPRINT);
        if (start == null) return e.isSprinting();  // fallback to server state
        if (stop == null) return true;
        return start.eventTick() > stop.eventTick();
    }

    /** True if client was sprinting within last {@code ticks} */
    public static boolean wasClientRecentlySprinting(@Nullable SprintTracker t, Entity e, int ticks) {
        if (t == null) return e.isSprinting();
        if (isClientSprinting(t, e)) return true;
        TickState stop = e.getTag(LAST_CLIENT_STOP_SPRINT);
        // Not currently sprinting (per the check above) and no stop ever recorded = never sprinted at all
        // (e.g. fresh join) - NOT "recently sprinting".
        if (stop == null) return false;
        return stop.isActiveWithin(ticks);
    }
}
