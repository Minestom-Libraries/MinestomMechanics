package io.github.term4.minestommechanics.tracking;

import io.github.term4.minestommechanics.util.TickClock;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single authority for an entity's ground/air timeline and per-tick motion. A {@link PlayerMoveEvent} listener
 * anchors the air clock to the client's rising move-packet (its true jump tick) and records each move-packet
 * delta, backing the reads {@link VelocityRule}s compose:
 * <ul>
 *   <li>{@link #ticksInAir(Entity)} - ticks since the move-packet that left the ground (the gravity-arc clock),</li>
 *   <li>{@link #launched(Entity)} - in an upward-launched arc (jump or knockback boost) vs a ledge walk-off,</li>
 *   <li>{@link #recentJump(Entity)} - the launch origin (yaw, sprint, takeoff horizontal seed),</li>
 *   <li>{@link #positionDelta(Entity)} - move-delta velocity (b/t), the client's last reported motion,</li>
 *   <li>{@link #onGround(Entity, int)} - ground state with a configurable fall-prediction depth.</li>
 * </ul>
 *
 * <p>Physics constants are read live (entity {@link Aerodynamics}, per-block ground friction); only the vanilla
 * numbers Minestom does not model ({@link #SPRINT_IMPULSE}, near-zero clamp) are fixed here.
 */
public final class MotionTracker {

    /** Server tick of the client's rising move-packet (its true jump tick); the gravity-arc anchor. */
    private static final Tag<Long> AIR_START_TICK = Tag.Transient("mm:air-start-tick");
    private static final Tag<Boolean> LAUNCHED = Tag.Transient("mm:launched");
    private static final Tag<Vec> MOVE_VELOCITY = Tag.Transient("mm:move-velocity");
    private static final Tag<MovePrev> MOVE_PREV = Tag.Transient("mm:move-prev");
    private static final Tag<LaunchStamp> LAUNCH_STAMP = Tag.Transient("mm:launch-stamp");
    /**
     * Server-side horizontal {@code motX/motZ} (b/t) anchored at the last ground/air transition. Read lazily, it
     * bleeds by vanilla friction (air drag, or block friction x drag on ground) over the elapsed ticks - identical
     * to one friction step per tick, but computed only at the next transition.
     */
    private static final Tag<MotState> MOT_H = Tag.Transient("mm:mot-h");

    /** Vanilla sprint-jump impulse ({@code bF()}: {@code motX -= sin(yaw)*0.2, motZ += cos(yaw)*0.2}). */
    public static final double SPRINT_IMPULSE = 0.2;
    /** Default {@link #onGround(Entity, int)} prediction depth: the vanilla {@code move()} 1-tick collision sweep. */
    public static final int DEFAULT_GROUND_TICKS = 1;
    /** Default block friction when the supporting block cannot be read (vanilla {@code 0.6}). */
    private static final double DEFAULT_BLOCK_FRICTION = 0.6;

    /** Previous move-packet position + ground flag: packet-granular transition detection + move-delta velocity. */
    private record MovePrev(double x, double y, double z, boolean onGround) {}
    /** Launch origin: server tick, facing yaw, sprinting at takeoff, pre-boost residual, and the boosted seed. */
    private record LaunchStamp(long tick, double yaw, boolean sprinting, Vec residualH, Vec seedH) {}
    /** Horizontal residual as of {@code sinceTick}, bleeding by air or ground friction per the held state. */
    private record MotState(Vec motH, long sinceTick, boolean airborne) {}

    /**
     * Launch origin exposed to {@link VelocityRule}s. {@code seedH} is the takeoff {@code motX/motZ}: the carried
     * {@code residualH} plus the {@link #SPRINT_IMPULSE} boost when sprinting (recompose a custom impulse from
     * {@code residualH} + {@code yaw}).
     */
    public record JumpInfo(double yaw, boolean sprinting, Vec residualH, Vec seedH) {}

    public MotionTracker() {}

    /** Starts the per-tick ground-state fallback poll (a tick behind {@link #onMove}; catches status-only onGround packets). */
    public void start() {
        MinecraftServer.getSchedulerManager()
                .buildTask(this::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule();
    }

    /** Listener anchoring the air clock + move-delta to the client's own move packets (ping-invariant). */
    public EventNode<@NotNull PlayerEvent> node() {
        EventNode<@NotNull PlayerEvent> node = EventNode.type("mm:motion-tracker", EventFilter.PLAYER);
        node.addListener(PlayerMoveEvent.class, this::onMove);
        return node;
    }

    private void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        long now = TickClock.now();
        boolean nowOnGround = e.isOnGround();
        Pos newPos = e.getNewPosition();
        // Compare against the previous move-packet (not p.isOnGround()/getPosition()) so transition
        // detection is independent of whether the event fires before or after Minestom applies the move.
        MovePrev prev = p.getTag(MOVE_PREV);
        p.setTag(MOVE_PREV, new MovePrev(newPos.x(), newPos.y(), newPos.z(), nowOnGround));
        if (prev == null) return; // need a baseline packet before a transition can be read

        // Move-delta velocity (b/t) straight off the client's packets. A once-per-tick getPosition() snapshot
        // races the hit (the attacker's packet often processes before the victim's move that tick, reading 0),
        // so we keep the victim's last reported motion instead.
        p.setTag(MOVE_VELOCITY, new Vec(newPos.x() - prev.x(), newPos.y() - prev.y(), newPos.z() - prev.z()));

        boolean wasOnGround = prev.onGround();
        double dy = newPos.y() - prev.y();

        // On ground (or flying): the ballistic arc is not running, so clear it. PRIMARY landing anchor - fires
        // on the very packet whose onGround flips; the tick() poll only ever trails this.
        if (nowOnGround || p.isFlying()) {
            if (nowOnGround) freezeOnLanding(p, now);
            p.removeTag(AIR_START_TICK);
            p.removeTag(LAUNCHED);
            return;
        }

        if (wasOnGround) {
            // Ground -> air: the client's rising packet = its true jump tick; anchor the arc's tick-zero here.
            // (The arc's launchOffset is an intra-tick packet-ordering phase - the hit is processed before the
            // victim's move packet that tick - see VelocityConfig.DEFAULT_LAUNCH_OFFSET.)
            if (dy > 0) latchLaunch(p, now, newPos.yaw()); // rising = launched, else walk-off
            p.setTag(AIR_START_TICK, now);
        } else {
            // Already airborne: ensure an anchor exists (e.g. joined mid-air) and latch a mid-air re-launch.
            if (p.getTag(AIR_START_TICK) == null) p.setTag(AIR_START_TICK, now);
            if (dy > 0 && !Boolean.TRUE.equals(p.getTag(LAUNCHED))) latchLaunch(p, now, newPos.yaw());
        }
    }

    /**
     * Latches a launch: folds the {@link #SPRINT_IMPULSE} boost onto the friction-bled {@code motX} residual and
     * freezes that as the takeoff seed the gravity arc bleeds, then re-anchors as airborne for the new flight.
     */
    private static void latchLaunch(Player p, long now, double yaw) {
        boolean sprinting = p.isSprinting();
        Vec residual = residualAt(p, p.getTag(MOT_H), now);
        Vec boost = sprinting ? sprintJumpImpulse(yaw) : Vec.ZERO;
        Vec seedH = residual.add(boost);
        p.setTag(MOT_H, new MotState(seedH, now, true));
        p.setTag(LAUNCHED, true);
        p.setTag(LAUNCH_STAMP, new LaunchStamp(now, yaw, sprinting, residual, seedH));
    }

    /** Vanilla {@code bF()} sprint-jump horizontal impulse for a facing yaw (b/t). */
    private static Vec sprintJumpImpulse(double yaw) {
        double r = Math.toRadians(yaw);
        return new Vec(-Math.sin(r) * SPRINT_IMPULSE, 0, Math.cos(r) * SPRINT_IMPULSE);
    }

    /**
     * The anchored residual bled forward to {@code now}: {@code motH x friction^(now - sinceTick)}, friction being
     * the live air drag, or block friction x drag on ground (vanilla reads the block under the player - default
     * {@code 0.6}, ice {@code 0.98}), then vanilla's {@code m()} near-zero clamp so a stale residual snaps to 0.
     */
    private static Vec residualAt(Player p, MotState s, long now) {
        if (s == null) return Vec.ZERO;
        int ticks = (int) Math.max(0, now - s.sinceTick());
        double hDrag = p.getAerodynamics().horizontalAirResistance();
        double friction = s.airborne() ? hDrag : blockFriction(p) * hDrag;
        Vec decayed = s.motH().mul(Math.pow(friction, ticks));
        double clamp = VelocityConfig.CLAMP;
        return new Vec(Math.abs(decayed.x()) < clamp ? 0.0 : decayed.x(), 0,
                Math.abs(decayed.z()) < clamp ? 0.0 : decayed.z());
    }

    /** Friction of the block under the player (vanilla {@code frictionFactor}; what {@code PhysicsUtils} reads). */
    private static double blockFriction(Player p) {
        var instance = p.getInstance();
        if (instance == null) return DEFAULT_BLOCK_FRICTION;
        return instance.getBlock(p.getPosition().sub(0, 0.5000001, 0)).registry().friction();
    }

    /**
     * At an air-&gt;ground transition, re-anchor the residual as grounded: bleed the airborne value to {@code now}
     * and stamp it ground state so the gap to the next jump bleeds by ground friction. No-op if already grounded.
     */
    private static void freezeOnLanding(Player p, long now) {
        MotState s = p.getTag(MOT_H);
        if (s == null || !s.airborne()) return;
        p.setTag(MOT_H, new MotState(residualAt(p, s, now), now, false));
    }

    private void tick() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            // FALLBACK only, and deliberately a tick behind: this scheduler task runs before serverTick() drains
            // move packets, so the onMove landing branch always anchors a real landing first. This catches the
            // leftovers onMove never sees (status-only onGround packets with no PlayerMoveEvent).
            if (p.isOnGround()) {
                freezeOnLanding(p, TickClock.now());
                p.removeTag(AIR_START_TICK);
                p.removeTag(LAUNCHED);
            }
        }
    }

    /**
     * Ticks since the client's rising move-packet (the gravity-arc clock) - counted from the client's own jump
     * tick, so it is ping-invariant. 0 when on the ground or with no air anchor.
     */
    public static int ticksInAir(Entity entity) {
        if (entity == null) return 0;
        Long start = entity.getTag(AIR_START_TICK);
        if (start == null) return 0;
        return (int) Math.max(0, TickClock.now() - start);
    }

    /**
     * Whether the entity is in an upward-launched arc (jump or knockback boost) rather than a ledge walk-off.
     * Latched on any upward motion while airborne, cleared on landing or flight.
     */
    public static boolean launched(Entity entity) {
        return entity instanceof Player p && Boolean.TRUE.equals(p.getTag(LAUNCHED));
    }

    /** Launch origin (yaw + sprint + takeoff horizontal residual/seed) while in a launch arc, or {@code null}. */
    public static @Nullable JumpInfo recentJump(Entity entity) {
        if (!(entity instanceof Player p) || !launched(p)) return null;
        LaunchStamp s = p.getTag(LAUNCH_STAMP);
        return s == null ? null : new JumpInfo(s.yaw(), s.sprinting(), s.residualH(), s.seedH());
    }

    /** Move-delta velocity (b/t) from the client's packets; players via the per-move snapshot, others via entity velocity. */
    public static Vec positionDelta(Entity entity) {
        if (entity instanceof Player p) {
            Vec d = p.getTag(MOVE_VELOCITY);
            return d == null ? Vec.ZERO : d;
        }
        return entity.getVelocity();
    }

    /** {@link #onGround(Entity, int)} with the {@link #DEFAULT_GROUND_TICKS default} 1-tick sweep. */
    public static boolean onGround(@Nullable Entity entity) {
        return onGround(entity, DEFAULT_GROUND_TICKS);
    }

    /**
     * Whether the entity is on the ground, with {@code ticks} of server-side fall prediction:
     * <ul>
     *   <li>{@code 0} - the raw client-reported {@link Entity#isOnGround()} flag (lags behind a laggy client's
     *       true landing),</li>
     *   <li>{@code >= 1} - the flag OR a collision sweep down the victim's predicted fall, mirroring vanilla
     *       {@code Entity.move()}'s {@code verticalCollisionBelow}: the probe descends {@code ticks}
     *       gravity-stepped ticks seeded from the client's reported {@link #positionDelta} (floored at one
     *       gravity step so a resting victim still registers), and reports whether a block clamps it. {@code 1}
     *       is the vanilla-faithful default - it stays correct while a laggy client's {@code onGround} packet is
     *       in flight.</li>
     * </ul>
     * A victim whose reported motion is rising is never swept grounded (vanilla {@code delta.y < 0}). The probe is
     * a pure <em>position</em> prediction off the reported motion - never the reconstructed knockback arc - so a
     * juggled victim's deep air clock cannot ground it mid-air. Non-players use the server flag directly.
     * Each {@code ticks >= 1} call runs a collision sweep; query it per hit/event, not per tick for every player.
     */
    // TODO: possible future overload onGround(entity, ticks, maxDist) - cap how far down the sweep may find
    //  ground - and a custom predicate interface if a real use case appears.
    public static boolean onGround(@Nullable Entity entity, int ticks) {
        if (entity == null) return false;
        if (ticks <= 0 || !(entity instanceof Player)) return entity.isOnGround();
        if (entity.isOnGround()) return true;
        if (entity.getInstance() == null) return false;

        double dy = positionDelta(entity).y();
        if (dy > 0) return false; // reported motion rising (jump / boost) - never grounded
        Aerodynamics aero = entity.getAerodynamics();
        double g = aero.gravity();
        double s = aero.verticalAirResistance();
        double vy = Math.min(dy, -g * s); // floor: a resting victim still probes one gravity step
        double probe = 0;
        for (int i = 0; i < ticks; i++) {
            probe += vy;
            vy = (vy - g) * s;
        }
        PhysicsResult r = CollisionUtils.handlePhysics(entity, new Vec(0, probe, 0));
        return r.isOnGround();
    }

    /**
     * Whether the entity is falling: airborne per {@link #onGround(Entity)} (so a laggy victim that has truly
     * landed is not "falling") and descending per {@link #positionDelta} (server-side {@code getVelocity()} does
     * not reflect a player's client-driven motion). A flying player is never falling.
     */
    public static boolean isFalling(@Nullable Entity entity) {
        if (entity == null) return false;
        if (entity instanceof Player p && p.isFlying()) return false;
        if (onGround(entity)) return false;
        // TODO: also not falling while in water, climbing (ladder/vine), cobweb, and maybe other.
        return positionDelta(entity).y() < 0;
    }
}
