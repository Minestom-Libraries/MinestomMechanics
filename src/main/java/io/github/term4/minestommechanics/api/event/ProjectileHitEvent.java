package io.github.term4.minestommechanics.api.event;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.CancellableEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a projectile hits an entity or a block, before the configured effects (knockback / damage / removal)
 * are applied. Cancel to suppress those effects (the projectile keeps flying). {@link #target()} is the hit
 * entity for an entity hit, {@code null} for a block hit. {@link #throwOrigin()} is the shooter's position + view
 * at THROW time (the snapshot a minigame can push knockback/teleport from), as distinct from their current pose.
 */
public class ProjectileHitEvent implements CancellableEvent {

    private final Entity projectile;
    private final @Nullable Entity shooter;
    private final @Nullable Entity target;
    private final Point hitPoint;
    private final @Nullable Pos throwOrigin;
    private boolean cancelled;

    public ProjectileHitEvent(Entity projectile, @Nullable Entity shooter, @Nullable Entity target,
                              Point hitPoint, @Nullable Pos throwOrigin) {
        this.projectile = projectile;
        this.shooter = shooter;
        this.target = target;
        this.hitPoint = hitPoint;
        this.throwOrigin = throwOrigin;
    }

    public @NotNull Entity projectile() { return projectile; }
    public @Nullable Entity shooter() { return shooter; }
    /** The hit entity, or {@code null} for a block hit. */
    public @Nullable Entity target() { return target; }
    public @NotNull Point hitPoint() { return hitPoint; }
    public boolean isBlockHit() { return target == null; }
    /** Whether the struck entity is the shooter itself. */
    public boolean isSelfHit() { return target != null && target == shooter; }
    /** The shooter's position + yaw/pitch stamped at THROW time, or {@code null} if there was no shooter. */
    public @Nullable Pos throwOrigin() { return throwOrigin; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
