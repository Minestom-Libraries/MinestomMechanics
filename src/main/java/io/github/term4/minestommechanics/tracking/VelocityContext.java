package io.github.term4.minestommechanics.tracking;

import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The input a {@link VelocityRule} composes - the velocity analog of {@code AttackEvent} for {@code CriticalRule}.
 * Bundles the entity and the {@link MotionTracker} reads (air-time, launch latch, position-delta motion, ground
 * state), so a rule written outside the library has the same primitives the built-ins use.
 */
public final class VelocityContext {

    private final Entity entity;
    private final @Nullable SprintTracker sprintTracker;

    private VelocityContext(Entity entity, @Nullable SprintTracker sprintTracker) {
        this.entity = entity;
        this.sprintTracker = sprintTracker;
    }

    /** Context without a sprint tracker (sprint reads degrade to the server-side {@link Entity#isSprinting()}). */
    public static VelocityContext of(Entity entity) {
        return new VelocityContext(entity, null);
    }

    /** Context with the given {@link SprintTracker}, so a rule can consult the client sprint state. */
    public static VelocityContext of(Entity entity, @Nullable SprintTracker sprintTracker) {
        return new VelocityContext(entity, sprintTracker);
    }

    public Entity entity() { return entity; }

    /** The sprint tracker backing the sprint reads, or {@code null} (reads fall back to server sprint state). */
    public @Nullable SprintTracker sprintTracker() { return sprintTracker; }

    /** Whether the entity was (server-side) sprinting within the last {@code ticks}. */
    public boolean wasRecentlySprinting(long ticks) {
        return SprintTracker.wasRecentlySprinting(sprintTracker, entity, ticks);
    }

    /** Whether the entity's <em>client</em> was sprinting within the last {@code ticks}. */
    public boolean wasClientRecentlySprinting(int ticks) {
        return SprintTracker.wasClientRecentlySprinting(sprintTracker, entity, ticks);
    }

    /** Position-delta velocity (blocks/tick); players via the per-tick snapshot, others via entity velocity. */
    public Vec positionDelta() { return MotionTracker.positionDelta(entity); }

    /** Ticks since the entity left the ground (0 on ground). */
    public int ticksInAir() { return MotionTracker.ticksInAir(entity); }

    /** Left the ground rising (jump or knockback boost) and still in that launch arc. */
    public boolean launched() { return MotionTracker.launched(entity); }

    /** Ground state with {@code ticks} of fall prediction (see {@link MotionTracker#onGround(Entity, int)}). */
    public boolean onGround(int ticks) { return MotionTracker.onGround(entity, ticks); }

    /** Launch origin (yaw + sprint + takeoff horizontal residual/seed) while in a launch arc, or {@code null}. */
    public @Nullable MotionTracker.JumpInfo recentJump() { return MotionTracker.recentJump(entity); }

    /** Server-side {@code Entity.collide} push residual (zero-Y, b/t); see {@link MotionTracker#entityPush}. */
    public Vec entityPush() { return MotionTracker.entityPush(entity); }
}
