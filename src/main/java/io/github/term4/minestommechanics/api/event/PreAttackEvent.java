package io.github.term4.minestommechanics.api.event;

import io.github.term4.minestommechanics.Services;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.CancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Fired the moment a hit is detected, before config resolution, the {@link AttackEvent}, and the ruleset - the earliest
 * combat hook. Carries the raw attacker/target so observers (reach logging, a future anticheat) can inspect or veto a hit
 * before it is processed; cancelling drops it. Today nothing cancels it (the reach log is observe-only).
 */
public final class PreAttackEvent implements CancellableEvent {

    private final Entity attacker;
    private final @Nullable Entity target;
    private final Services services;
    private boolean cancelled;

    public PreAttackEvent(Entity attacker, @Nullable Entity target, Services services) {
        this.attacker = attacker;
        this.target = target;
        this.services = services;
    }

    public Entity attacker() { return attacker; }

    /** The hit target, or {@code null} for a target-less detection (e.g. a raytraced swing miss). */
    public @Nullable Entity target() { return target; }

    public Services services() { return services; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
