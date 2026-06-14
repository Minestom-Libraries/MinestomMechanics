package io.github.term4.minestommechanics.api.event;

import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.CancellableEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired once a projectile {@link #projectile() entity} has been built and configured but BEFORE it enters the world.
 * One event covers the whole launch:
 * <ul>
 *   <li><b>cancel</b> - {@code setCancelled(true)} discards the entity; it never spawns.</li>
 *   <li><b>redirect</b> - mutate {@link #setSpawnPos}/{@link #setVelocity} (velocity is b/t).</li>
 *   <li><b>attach</b> - keep a reference to {@link #projectile()} to add cosmetics/behavior (a particle trail, a
 *       per-tick task, metadata). Tasks scheduled here run on the next tick, once the entity is in its instance.</li>
 * </ul>
 * To react to the END of flight (e.g. spawn an entity where it lands), use {@link ProjectileHitEvent} instead.
 */
public class ProjectileLaunchEvent implements CancellableEvent {

    private final ProjectileSnapshot snapshot;
    private final Entity projectile;
    private Pos spawnPos;
    private Vec velocity;
    private boolean cancelled;

    public ProjectileLaunchEvent(ProjectileSnapshot snapshot, Entity projectile, Pos spawnPos, Vec velocity) {
        this.snapshot = snapshot;
        this.projectile = projectile;
        this.spawnPos = spawnPos;
        this.velocity = velocity;
    }

    public ProjectileSnapshot snapshot() { return snapshot; }
    public @Nullable Entity shooter() { return snapshot.shooter(); }

    /** The built projectile entity, not yet in the world. Attach trails/behavior, or cancel to discard it. */
    public @NotNull Entity projectile() { return projectile; }

    public @NotNull Pos spawnPos() { return spawnPos; }
    public void setSpawnPos(@NotNull Pos pos) { this.spawnPos = pos; }

    /** Initial velocity in blocks/tick. */
    public @NotNull Vec velocity() { return velocity; }
    public void setVelocity(@NotNull Vec velocityBt) { this.velocity = velocityBt; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
