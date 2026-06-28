package io.github.term4.minestommechanics.api.event;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.CancellableEvent;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Fired after per-entity exposure/falloff is computed, before knockback + damage apply. Cancel to abort. {@link #targets()}
 * is the live, mutable result set - a listener reads each {@link Target}'s exposure and retunes its knockback/damage (or
 * drops it). Block destruction is delegated: a listener handles it off {@link #center()} + {@link #power()}.
 */
public class ExplosionEvent implements CancellableEvent {

    /** One entity caught in the explosion: read-only {@code distance}/{@code exposure}, mutable {@code knockback} (the falloff push) + {@code damage}. */
    public static final class Target {
        private final Entity entity;
        private final double distance;
        private final float exposure;
        private @Nullable Vec knockback;
        private float damage;

        public Target(@NotNull Entity entity, double distance, float exposure, @Nullable Vec knockback, float damage) {
            this.entity = entity;
            this.distance = distance;
            this.exposure = exposure;
            this.knockback = knockback;
            this.damage = damage;
        }

        public @NotNull Entity entity() { return entity; }
        /** Distance from the entity to the explosion center (blocks). */
        public double distance() { return distance; }
        /** Raytraced line-of-sight fraction (0..1); 1.0 when exposure is disabled. */
        public float exposure() { return exposure; }
        /** The explosion's falloff push for this entity, or {@code null} (entity exactly on the center). */
        public @Nullable Vec knockback() { return knockback; }
        public void setKnockback(@Nullable Vec knockback) { this.knockback = knockback; }
        public float damage() { return damage; }
        public void setDamage(float damage) { this.damage = damage; }
    }

    private final Instance instance;
    private final Point center;
    private final float power;
    private final @Nullable Entity source;
    private final boolean fire;
    private final List<Target> targets;
    private boolean cancelled;

    public ExplosionEvent(@NotNull Instance instance, @NotNull Point center, float power, @Nullable Entity source,
                          boolean fire, @NotNull List<Target> targets) {
        this.instance = instance;
        this.center = center;
        this.power = power;
        this.source = source;
        this.fire = fire;
        this.targets = targets;
    }

    public @NotNull Instance instance() { return instance; }
    public @NotNull Point center() { return center; }
    public float power() { return power; }
    public @Nullable Entity source() { return source; }
    public boolean fire() { return fire; }

    /** The live, mutable set of affected entities and their computed effects; edit or {@code removeIf} to alter the outcome. */
    public @NotNull List<Target> targets() { return targets; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
