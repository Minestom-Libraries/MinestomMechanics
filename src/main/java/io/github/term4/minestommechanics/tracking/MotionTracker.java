package io.github.term4.minestommechanics.tracking;

import io.github.term4.minestommechanics.util.TickClock;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// TODO: Look into ladders, liquid, potion effects
/**
 * Single authority for an entity's ground/air timeline and server-tracked motion - the replica of vanilla
 * {@code this.motX/motY/motZ}. A {@link PlayerMoveEvent} listener anchors the air clock to the client's rising
 * move-packet (its true jump tick) and records each move-packet delta; a per-tick task forward-simulates the
 * vertical mot and the entity-push residual. Together they back the reads {@link VelocityRule}s compose:
 * <ul>
 *   <li>{@link #serverMotY(Entity, int, boolean)} - the ticked vanilla {@code motY} (clamp, {@code move()}
 *       collide-zero, gravity - carries the emergent quirks like early-collide re-falls),</li>
 *   <li>{@link #horizontalMot(Entity, int)} - the friction-bled horizontal impulse residual (the {@code bF()}
 *       sprint-jump boost; melee knockback is broadcast-then-restored in vanilla and never carried),</li>
 *   <li>{@link #entityPush(Entity)} - the server-side {@code Entity.collide} push residual (1.8 {@code bL()}
 *       accumulates a {@code 0.05} separation impulse into {@code motX/motZ} every tick entities overlap),</li>
 *   <li>{@link #onGround(Entity, int)} - ground state from the sim's collision, with a configurable
 *       fall-prediction depth,</li>
 *   <li>{@link #ticksInAir(Entity)} / {@link #launched(Entity)} / {@link #recentJump(Entity)} - the air clock
 *       and launch origin (also the fallback reconstruction for non-players),</li>
 *   <li>{@link #positionDelta(Entity)} - move-delta velocity (b/t), the client's last reported motion.</li>
 * </ul>
 *
 * <p>Physics constants are read live (entity {@link Aerodynamics}, per-block ground friction); only the vanilla
 * numbers Minestom does not model ({@link #SPRINT_IMPULSE}, near-zero clamp) are fixed here.
 */
public final class MotionTracker implements Tracker {

    /** Server tick of the client's rising move-packet (its true jump tick); the gravity-arc anchor. */
    private static final Tag<Long> AIR_START_TICK = Tag.Transient("mm:air-start-tick");
    private static final Tag<Boolean> LAUNCHED = Tag.Transient("mm:launched");
    private static final Tag<Vec> MOVE_VELOCITY = Tag.Transient("mm:move-velocity");
    private static final Tag<MovePrev> MOVE_PREV = Tag.Transient("mm:move-prev");
    private static final Tag<LaunchStamp> LAUNCH_STAMP = Tag.Transient("mm:launch-stamp");
    /**
     * Server-side horizontal {@code motX/motZ} (b/t) - vanilla {@code this.motX/motZ}: the victim's own simulated
     * motion (the {@code bF()} sprint-jump boost via {@link #latchLaunch}), <strong>not</strong> the player's running
     * velocity (the server never folds WASD into {@code motX}) and <strong>not</strong> any prior knockback (vanilla
     * {@code EntityHuman.attack} restores the pre-knockback velocity after broadcasting it - see {@link #horizontalMot}).
     * Anchored at its last write and read lazily, it bleeds by vanilla friction (air drag, or block friction x drag on
     * ground) over the elapsed ticks - one friction step per tick. Folded into every hit (grounded or air), so a chained
     * victim's carried sprint boost opposes the incoming knockback exactly like vanilla.
     */
    private static final Tag<MotState> MOT_H = Tag.Transient("mm:mot-h");
    /**
     * Server-side {@code Entity.collide} push residual: what vanilla {@code motX/motZ} additionally carries
     * from overlapping entities. Accumulated per tick (vanilla order: travel bleeds, then {@code bL()} adds),
     * invisible to the client until a velocity broadcast folds it - the 1.8 "generic damage pushes you out of
     * a player" quirk. {@link VelocityConfig#entityPush()} gates whether an arc folds it.
     */
    private static final Tag<Vec> ENTITY_PUSH = Tag.Transient("mm:entity-push");

    /**
     * Vanilla {@code this.motY}, forward-simulated per server tick like {@code EntityLiving.m()}: near-zero clamp,
     * then {@code move()} - a collision probe of the attempted descent at the client-synced position, landing
     * zeroing motY ({@code Block.a}) - then gravity. Seeded {@code 0.42} by {@link #latchLaunch} (the
     * {@code PlayerConnection} jump-detection analog: fires on any rising ground departure, jumps AND ground KB
     * launches). Melee knockback never touches it (vanilla restores the pre-KB velocity after broadcasting).
     * This produces the emergent vanilla quirks the analytic arc cannot: a restored, deeper-than-client descent
     * collides EARLY, zeroes, and re-falls from 0 while the client finishes landing - so a hit right at touchdown
     * folds a shallow re-fall step (the "unlaunched air-tick-2 on a ground hit" fold).
     */
    private static final Tag<VertSim> VERT_SIM = Tag.Transient("mm:vert-sim");

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
     * Ticked {@code motY} state. {@code clamped} = vanilla (the {@code m()} 0.005 apex reseed); {@code raw} = no
     * reseed (presets with {@code clampY(0)}, e.g. Hypixel's calibration). Index {@code [0]} is the latest
     * end-of-tick value; older slots back the {@code launchOffset} history lookback (debug knob - may go away).
     */
    private static final class VertSim {
        static final int HISTORY = 4;
        final double[] clamped = new double[HISTORY];
        final double[] raw = new double[HISTORY];
        /** Last {@code move()} probe clamped a descent - vanilla's collision {@code onGround}. */
        boolean collided;
    }

    /**
     * Launch origin exposed to {@link VelocityRule}s. {@code seedH} is the takeoff {@code motX/motZ}: the carried
     * {@code residualH} plus the {@link #SPRINT_IMPULSE} boost when sprinting (recompose a custom impulse from
     * {@code residualH} + {@code yaw}).
     */
    public record JumpInfo(double yaw, boolean sprinting, Vec residualH, Vec seedH) {}

    public MotionTracker() {}

    /** Starts the per-tick ground-state fallback poll (a tick behind {@link #onMove}; catches status-only onGround packets). */
    @Override
    public void start() {
        MinecraftServer.getSchedulerManager()
                .buildTask(this::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule();
    }

    /** Listener anchoring the air clock + move-delta to the client's own move packets (ping-invariant). */
    @Override
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
        // PlayerConnection jump-detection analog: a rising ground departure calls bF() -> motY = 0.42 - for real
        // jumps AND ground KB launches alike (the server cannot tell them apart). Overwrites the current sim value
        // mid-tick, exactly like bF() firing in the packet phase after m() ran (the launch-tick fold reads 0.42 raw).
        VertSim sim = p.getTag(VERT_SIM);
        if (sim != null) {
            sim.clamped[0] = VelocityConfig.JUMP_VELOCITY;
            sim.raw[0] = VelocityConfig.JUMP_VELOCITY;
            sim.collided = false;
        }
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
        Vec decayed = s.motH().mul(Math.pow(frictionPerTick(p, s.airborne()), ticks));
        double clamp = VelocityConfig.CLAMP;
        return new Vec(Math.abs(decayed.x()) < clamp ? 0.0 : decayed.x(), 0,
                Math.abs(decayed.z()) < clamp ? 0.0 : decayed.z());
    }

    /**
     * Per-tick horizontal friction of the server-side mot model: air drag while airborne, block friction x air
     * drag on the ground. Both the {@code MOT_H} residual and the entity-push residual bleed by this law.
     */
    static double frictionPerTick(Player p, boolean airborne) {
        double hDrag = p.getAerodynamics().horizontalAirResistance();
        return airborne ? hDrag : blockFriction(p) * hDrag;
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
            // Vanilla m() order: clamp -> move (collide) -> gravity, then bL() adds entity pushes.
            tickVertSim(p);
            tickEntityPush(p);
        }
    }

    /** One vanilla {@code m()} travel step for the ticked motY: clamp, {@code move()} collide-zero, gravity. */
    private static void tickVertSim(Player p) {
        VertSim sim = p.getTag(VERT_SIM);
        if (sim == null) p.setTag(VERT_SIM, sim = new VertSim());
        if (p.isFlying() || p.getInstance() == null) {
            shift(sim.clamped, 0);
            shift(sim.raw, 0);
            sim.collided = p.isOnGround();
            return;
        }
        Aerodynamics aero = p.getAerodynamics();
        double g = aero.gravity(), s = aero.verticalAirResistance();
        double c = sim.clamped[0], r = sim.raw[0];
        if (Math.abs(c) < VelocityConfig.CLAMP) c = 0; // vanilla m() start-of-tick near-zero clamp (apex reseed)
        // move(): collide the attempted descent at the client-synced position; landing zeroes motY (Block.a).
        // The quirk chain lives here: a restored, deeper-than-client arc collides EARLY, zeroes, and re-falls
        // from 0 while the client finishes its descent - a touchdown hit then folds the shallow re-fall step.
        boolean collidedC = c < 0 && CollisionUtils.handlePhysics(p, new Vec(0, c, 0)).isOnGround();
        boolean collidedR = r < 0 && (r == c ? collidedC : CollisionUtils.handlePhysics(p, new Vec(0, r, 0)).isOnGround());
        if (collidedC) c = 0;
        if (collidedR) r = 0;
        shift(sim.clamped, (c - g) * s);
        shift(sim.raw, (r - g) * s);
        sim.collided = collidedC;
    }

    private static void shift(double[] h, double v) {
        for (int i = h.length - 1; i > 0; i--) h[i] = h[i - 1];
        h[0] = v;
    }

    /**
     * Whether the ticked sim's last {@code move()} probe clamped a descent - vanilla's collision
     * {@code onGround}, a cheap tag read. Fires ticks before a laggy client's landing packet (the server arc
     * is deeper than the stale position), so ground-coupled producers (fall damage) can end a fall in sync
     * with the combat ground checks instead of trailing the client flag.
     */
    public static boolean simCollided(Entity entity) {
        if (!(entity instanceof Player p)) return false;
        VertSim sim = p.getTag(VERT_SIM);
        return sim != null && sim.collided;
    }

    /**
     * The ticked vanilla {@code motY} (b/t), or {@code null} when no sim state exists (non-player / not yet
     * ticked). {@code lookback >= 0} reads that many end-of-tick values into the past (the {@code launchOffset}
     * debug knob, already phase-corrected by the caller); {@code clamped} selects the vanilla apex-reseed
     * variant vs the no-reseed one ({@code clampY(0)} presets).
     */
    public static @Nullable Double serverMotY(Entity entity, int lookback, boolean clamped) {
        if (!(entity instanceof Player p)) return null;
        VertSim sim = p.getTag(VERT_SIM);
        if (sim == null) return null;
        int i = Math.min(Math.max(lookback, 0), VertSim.HISTORY - 1);
        return clamped ? sim.clamped[i] : sim.raw[i];
    }

    // --- Entity.collide push residual (1.8 bL()) ---

    /** Vanilla {@code bL()} list range: own bounding box grown {@code 0.2} per side (expand takes total size). */
    private static final double PUSH_GROW = 0.4;
    /** Vanilla {@code Entity.collide} impulse. */
    private static final double PUSH_IMPULSE = 0.05;
    /** Vanilla skips the push below this absMax horizontal distance (perfectly stacked players never separate). */
    private static final double PUSH_MIN_DIST = 0.01;
    /** Chunk-query radius around the player; generous enough for any overlapping entity's half-width. */
    private static final double PUSH_QUERY_RANGE = 3.0;

    /** Vanilla per-tick order: travel bleeds the existing residual, then {@code bL()} adds this tick's pushes raw. */
    private static void tickEntityPush(Player p) {
        Vec acc = bleedPush(p, entityPush(p)).add(pushesFor(p));
        if (acc.isZero()) p.removeTag(ENTITY_PUSH);
        else p.setTag(ENTITY_PUSH, acc);
    }

    /** One friction step + vanilla's near-zero clamp, mirroring the travel step the residual rode in vanilla. */
    private static Vec bleedPush(Player p, Vec acc) {
        if (acc.isZero()) return acc;
        Vec decayed = acc.mul(frictionPerTick(p, !p.isOnGround()));
        double clamp = VelocityConfig.CLAMP;
        return new Vec(Math.abs(decayed.x()) < clamp ? 0.0 : decayed.x(), 0,
                Math.abs(decayed.z()) < clamp ? 0.0 : decayed.z());
    }

    /** This tick's incoming pushes: vanilla {@code Entity.collide} from every overlapping living entity. */
    private static Vec pushesFor(Player p) {
        var instance = p.getInstance();
        if (instance == null) return Vec.ZERO;
        var range = p.getBoundingBox().expand(PUSH_GROW, 0, PUSH_GROW);

        double px = 0, pz = 0;
        for (Entity other : instance.getNearbyEntities(p.getPosition(), PUSH_QUERY_RANGE)) {
            if (other == p || !(other instanceof LivingEntity living) || living.isDead()) continue;
            if (other instanceof Player op && op.getGameMode() == GameMode.SPECTATOR) continue;
            if (!range.intersectEntity(p.getPosition(), other)) continue;

            double dx = other.getPosition().x() - p.getPosition().x();
            double dz = other.getPosition().z() - p.getPosition().z();
            // Vanilla quirk: normalized by sqrt(absMax), not the euclidean distance (MathHelper.a(d0, d1)).
            double absMax = Math.max(Math.abs(dx), Math.abs(dz));
            if (absMax < PUSH_MIN_DIST) continue;
            double norm = Math.sqrt(absMax);
            double scale = Math.min(1.0, 1.0 / norm) / norm * PUSH_IMPULSE;

            // The pair collides once from each side's bL() pass: other players pass every tick; CraftBukkit
            // ticks a non-player living's pass only every other tick.
            int passes = other instanceof Player || other.getAliveTicks() % 2 != 0 ? 2 : 1;
            px -= dx * scale * passes;
            pz -= dz * scale * passes;
        }
        return new Vec(px, 0, pz);
    }

    /**
     * The entity's current server-side push residual (zero-Y, b/t), or {@link Vec#ZERO}. {@link VelocityRule}
     * arcs fold this into their horizontal estimate when {@link VelocityConfig#entityPush()} is enabled, so
     * both the melee friction fold and the hurt velocity broadcast carry it, exactly like vanilla {@code motX/motZ}.
     */
    public static Vec entityPush(Entity entity) {
        if (!(entity instanceof Player p)) return Vec.ZERO;
        Vec v = p.getTag(ENTITY_PUSH);
        return v == null ? Vec.ZERO : v;
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

    /**
     * Server-tracked horizontal mot (b/t) - vanilla {@code this.motX/motZ} - friction-bled to {@code now + tickOffset}
     * ({@code tickOffset} the arc's launch-phase correction). The value every hit folds, in any ground state.
     *
     * <p>This is the victim's <em>own</em> simulated motion (the {@code bF()} sprint-jump boost, friction-decayed) -
     * <strong>not</strong> any prior knockback. Vanilla {@code EntityHuman.attack} saves {@code motX/motY/motZ}
     * before {@code damageEntity}, lets {@code a()} overwrite them with the knockback to broadcast it, then
     * <em>restores</em> the saved values - so the knockback is sent to the client and discarded server-side, never
     * folded into the next hit. (Why a chained victim's fold opposes via the carried sprint boost, not the KB.)
     */
    public static Vec horizontalMot(Entity entity, int tickOffset) {
        if (!(entity instanceof Player p)) return Vec.ZERO;
        return residualAt(p, p.getTag(MOT_H), TickClock.now() + tickOffset);
    }

    /** Move-delta velocity (b/t) from the client's packets; players via the per-move snapshot, others via entity velocity. */
    public static Vec positionDelta(Entity entity) {
        if (entity instanceof Player p) {
            Vec d = p.getTag(MOVE_VELOCITY);
            return d == null ? Vec.ZERO : d;
        }
        // Minestom entity velocity is blocks/second; this read is blocks/tick everywhere.
        return entity.getVelocity().div(ServerFlag.SERVER_TICKS_PER_SECOND);
    }

    /** {@link #onGround(Entity, int)} with the {@link #DEFAULT_GROUND_TICKS default} 1-tick sweep. */
    public static boolean onGround(@Nullable Entity entity) {
        return onGround(entity, DEFAULT_GROUND_TICKS);
    }

    /**
     * Whether the entity is on the ground, mirroring vanilla {@code Entity.move()}'s {@code onGround = verticalCollision
     * && motY < 0}:
     * <ul>
     *   <li>{@code 0} - the raw client-reported {@link Entity#isOnGround()} flag (lags behind a laggy client's
     *       true landing),</li>
     *   <li>{@code >= 1} - the flag OR a downward collision probe seeded from the entity's <em>server-simulated
     *       {@code motY}</em> (vanilla {@code this.motY}: the reconstructed gravity arc at the current air tick),
     *       <strong>not</strong> the client's reported {@link #positionDelta}. This is the crucial vanilla
     *       detail: the server arc keeps accelerating each air tick, so a high-ping victim whose last packet is
     *       shallow/stale still grounds about as fast as vanilla - whereas probing the stale client delta leaves
     *       them "airborne" far too long and they eat spurious falling knockback. {@code 1} probes one arc tick
     *       (vanilla's single {@code move()} step); larger values forward-predict further (more lenient grounding).</li>
     * </ul>
     * A rising arc (jump/boost apex) is never grounded (vanilla {@code motY < 0}). Non-players use the server flag
     * directly. Each {@code ticks >= 1} call runs a collision sweep; query it per hit/event, not per tick for every player.
     */
    public static boolean onGround(@Nullable Entity entity, int ticks) {
        if (entity == null) return false;
        if (ticks <= 0 || !(entity instanceof Player p)) return entity.isOnGround();
        if (entity.isOnGround()) return true;
        if (entity.getInstance() == null) return false;

        // Vanilla's effective onGround: the last move()'s collision result (the ticked sim's probe).
        if (simCollided(p)) return true;

        Aerodynamics aero = entity.getAerodynamics();
        double g = aero.gravity();
        double s = aero.verticalAirResistance();
        double vy = serverSimMotY(p, g, s); // vanilla this.motY (the server gravity arc), not the client delta
        if (vy >= 0) return false; // arc rising - never grounded
        double probe = 0;
        for (int i = 0; i < ticks; i++) {
            probe += vy;
            vy = (vy - g) * s;
        }
        PhysicsResult r = CollisionUtils.handlePhysics(entity, new Vec(0, probe, 0));
        return r.isOnGround();
    }

    /**
     * Server-side {@code motY} - vanilla {@code this.motY}: the ticked sim's latest value when present, else the
     * analytic gravity arc (launch seed gravity-stepped {@link #ticksInAir} times). Unlike the client
     * {@link #positionDelta}, it keeps accelerating downward, so a high-ping victim's fall is probed at the depth
     * vanilla actually sees.
     */
    private static double serverSimMotY(Player p, double g, double s) {
        VertSim sim = p.getTag(VERT_SIM);
        if (sim != null) return sim.clamped[0];
        int air = ticksInAir(p);
        double vy = launched(p) ? VelocityConfig.JUMP_VELOCITY : 0.0;
        for (int i = 0; i < air; i++) vy = (vy - g) * s;
        return vy;
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
