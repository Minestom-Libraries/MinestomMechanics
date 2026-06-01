package io.github.term4.minestommechanics.util;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks when entities were last in the air. Used for "on ground in past N ticks" predicates.
 */
public final class GroundTracker {

    public static final Tag<TickState> LAST_AIRBORNE_STATE = Tag.Transient("mm:last-airborne-state");
    /** Tick when entity was last on ground. Used to compute ticks-in-air for gravity-predicted velocity. */
    public static final Tag<Long> LAST_GROUND_TICK = Tag.Transient("mm:last-ground-tick");
    private static final double TPS = ServerFlag.SERVER_TICKS_PER_SECOND;
    // TODO: Make this landing epsilon value configurable in the presets, make sure raytracing is correct
    private static final double LANDING_EPSILON = 0.005;

    public GroundTracker() {}

    /**
     * Starts the ground tracker. Runs every tick to update lastAirborneState and lastGroundTick.
     */
    public void start() {
        MinecraftServer.getSchedulerManager()
                .buildTask(this::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule();
    }

    private void tick() {
        long now = TickClock.now();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (p.isOnGround() || predictsLandingSoon(p)) {
                p.setTag(LAST_GROUND_TICK, now);
            } else {
                p.setTag(LAST_AIRBORNE_STATE, new TickState(now, 0));
            }
        }
    }

    /**
     * Ticks since entity last touched ground. 0 when on ground (we update LAST_GROUND_TICK every tick)
     * or no history. Intentionally does NOT check isOnGround()—it can be 1 tick late from the client,
     * which would wrongly zero out gravity dampening at the moment of air hits.
     */
    public static int getTicksInAir(Entity entity) {
        if (entity == null) return 0;
        Long last = entity.getTag(LAST_GROUND_TICK);
        if (last == null) return 0;
        return (int) Math.max(0, TickClock.now() - last);
    }

    /**
     * Returns true if the entity was on ground for the entire past N ticks.
     * When in air, we set lastAirborneState = now. So "on ground in past N" = last airborne was more than N ticks ago.
     */
    public static boolean isOnGround(@Nullable GroundTracker tracker, Entity entity, int ticks) {
        if (!(entity instanceof LivingEntity le)) return false;
        if (le.isOnGround()) {
            TickState state = entity.getTag(LAST_AIRBORNE_STATE);
            if (state == null) return true;
            return state.isStaleAfter(ticks);
        }
        return false;
    }

    /**
     * Returns true if the entity should be treated as "landing soon" for knockback logic.
     * Uses a small feet-distance epsilon while falling and a short lookahead so we do not rely on
     * the client onGround flag, which can be 1 tick late.
     */
    public static boolean predictsLandingSoon(Entity entity) {
        if (entity == null) return false;
        Instance instance = entity.getInstance();
        if (instance == null) return entity.isOnGround();
        if (entity.isOnGround()) return true;

        Vec vel = entity.getVelocity();
        double vyTick = vel.y() / TPS;
        if (vyTick >= 0) return false;

        double x = entity.getPosition().x();
        double y = entity.getPosition().y();
        double z = entity.getPosition().z();

        if (isSolidWithinEpsilon(instance, x, y, z, LANDING_EPSILON)) return true;

        double nextY = y + vyTick;
        if (isSolidWithinEpsilon(instance, x, nextY, z, LANDING_EPSILON)) return true;

        // TODO: Update to work dynamically vs hard coded
        // 2nd-tick lookahead (vanilla-ish): v = v * 0.98 - 0.08
        double vyNext = (vyTick * 0.98) - 0.08;
        double next2Y = nextY + vyNext;
        return isSolidWithinEpsilon(instance, x, next2Y, z, LANDING_EPSILON);
    }

    private static boolean isSolidWithinEpsilon(Instance instance, double x, double y, double z, double epsilon) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int by = (int) Math.floor(y - epsilon);
        Block block = instance.getBlock(bx, by, bz);
        return block != null && !block.isAir() && block.isSolid();
    }
}
