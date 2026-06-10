package io.github.term4.minestommechanics.tracking;

import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.PhysicsUtils;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;

/**
 * Strategy for estimating an entity's velocity (b/t) for the knockback friction term. A single-method interface
 * with a {@link #DEFAULT} and static factories, mirroring {@code AttackEvent.CriticalRule}. Two base rules,
 * mixable per axis with {@link #split}:
 * <ul>
 *   <li>{@link #simulated(VelocityConfig)} - reconstruct the launch arc from {@link MotionTracker}'s air clock,
 *       seeded by the jump + sprint-jump impulse, advanced by gravity + drag read live from the entity.</li>
 *   <li>{@link #delta()} - trust the client's reported motion (position delta, which includes knockback).</li>
 * </ul>
 */
@FunctionalInterface
public interface VelocityRule {

    /** How the {@link #simulated(VelocityConfig) simulated} arc evaluates an axis. */
    enum ArcStyle {
        /**
         * Step per tick via {@link PhysicsUtils#updateVelocity}, zeroing {@code motY} below
         * {@link VelocityConfig#clampY()} before each step so the apex reseeds from 0 - vanilla-faithful 1.8.
         */
        PER_TICK,
        /** Analytic {@code v0*s^t - g*s*(1 - s^t)/(1 - s)} (no per-tick loop); smooths through the apex - cheaper. */
        CLOSED
    }

    /** Estimated velocity (b/t) for the knockback friction term. */
    Vec estimate(VelocityContext ctx);

    /** Rule used when a config does not specify one (the default {@link #simulated()} arc). */
    VelocityRule DEFAULT = simulated();

    /** Position delta - trusts the client's reported motion. Unclamped. */
    static VelocityRule delta() { return VelocityContext::positionDelta; }

    /** Reconstructed launch arc with vanilla-default knobs. */
    static VelocityRule simulated() { return simulated(VelocityConfig.defaults()); }

    /** Reconstructed launch arc with the given {@link VelocityConfig}. */
    static VelocityRule simulated(VelocityConfig cfg) { return ctx -> arc(ctx, cfg); }

    /** Mixes two rules per axis: horizontal (x/z) from {@code horizontal}, vertical (y) from {@code vertical}. */
    static VelocityRule split(VelocityRule horizontal, VelocityRule vertical) {
        return ctx -> {
            Vec h = horizontal.estimate(ctx);
            return new Vec(h.x(), vertical.estimate(ctx).y(), h.z());
        };
    }

    /**
     * Reconstructs the launch arc: seeds the jump + sprint-jump (from {@link MotionTracker.JumpInfo#seedH()}),
     * advances by {@code ticksInAir + launchOffset} ticks (a walk-off seeds from rest at {@code ticksInAir + 1}),
     * evaluates each axis by its {@link ArcStyle}, and applies the per-component clamp. The air clock free-runs
     * through a combo (the vertical seed is planted at launch, never re-seeded from the knockback just dealt), so
     * a juggled victim's fold decays down the gravity curve; {@link VelocityConfig#maxAirTicks()} caps how far.
     * Gravity and drag are the entity's live {@link Aerodynamics}; the air clock is gated on
     * {@link MotionTracker#onGround} with the config's {@link VelocityConfig#groundTicks()}, so a laggy victim
     * that has truly landed folds the resting arc instead of a stale descent (mirrors vanilla clocking its motY
     * off the {@code move()} collision result, not the client onGround flag).
     */
    private static Vec arc(VelocityContext ctx, VelocityConfig cfg) {
        boolean grounded = ctx.onGround(cfg.groundTicks());
        boolean launched = !grounded && ctx.launched();
        int air = grounded ? 0 : ctx.ticksInAir();
        if (cfg.maxAirTicks() != null) air = Math.min(air, cfg.maxAirTicks());
        int ticks = launched ? air + cfg.launchOffset() : air + 1;
        MotionTracker.JumpInfo j = ctx.recentJump();
        Vec seedH = j != null ? j.seedH() : Vec.ZERO;
        Vec seed = launched ? new Vec(seedH.x(), cfg.seed(), seedH.z()) : Vec.ZERO;
        Aerodynamics aero = ctx.entity().getAerodynamics();

        boolean usePerTick = cfg.horizontalStyle() == ArcStyle.PER_TICK || cfg.verticalStyle() == ArcStyle.PER_TICK;
        boolean useClosed = cfg.horizontalStyle() == ArcStyle.CLOSED || cfg.verticalStyle() == ArcStyle.CLOSED;
        Vec stepped = usePerTick ? simulate(ctx.entity(), aero, cfg.clampY(), seed, ticks) : Vec.ZERO;
        Vec analytic = useClosed ? closedArc(aero, cfg.seed(), seed, ticks, launched) : Vec.ZERO;

        Vec h = cfg.horizontalStyle() == ArcStyle.CLOSED ? analytic : stepped;
        Vec v = cfg.verticalStyle() == ArcStyle.CLOSED ? analytic : stepped;
        return clamp(new Vec(h.x(), v.y(), h.z()), cfg.clampX(), cfg.clampY(), cfg.clampZ());
    }

    /**
     * Advances {@code seed} by {@code ticks} airborne ticks via {@link PhysicsUtils#updateVelocity} (gravity +
     * air resistance, {@code onGround=false}), zeroing {@code motY} below {@code clampY} before each step. The
     * block getter is unused while airborne, so a null instance is harmless.
     */
    private static Vec simulate(Entity entity, Aerodynamics aero, double clampY, Vec seed, int ticks) {
        if (ticks <= 0) return seed;
        Vec vel = seed;
        var pos = entity.getPosition();
        var blocks = entity.getInstance();
        for (int t = 0; t < ticks; t++) {
            if (Math.abs(vel.y()) < clampY) vel = vel.withY(0);
            vel = PhysicsUtils.updateVelocity(pos, vel, blocks, aero, true, false, false, false);
        }
        return vel;
    }

    /**
     * Analytic arc: horizontal {@code seedH * hDrag^ticks}, vertical {@code v0*s^t - g*s*(1 - s^t)/(1 - s)},
     * clamped to {@code [terminalVy, seedY]}. No apex reseed (~0.003 b/t shallow per descending tick vs
     * {@link #simulate}). {@code ticks <= 0} returns the launch-tick {@code -g*s}.
     */
    private static Vec closedArc(Aerodynamics aero, double seedY, Vec seed, int ticks, boolean launched) {
        double g = aero.gravity();
        double s = aero.verticalAirResistance();
        double hpow = Math.pow(aero.horizontalAirResistance(), Math.max(0, ticks));
        double hx = seed.x() * hpow;
        double hz = seed.z() * hpow;
        double vy;
        if (ticks <= 0) {
            vy = -g * s;
        } else {
            double scalePow = Math.pow(s, ticks);
            double v0 = launched ? seed.y() : 0.0;
            vy = v0 * scalePow - g * s * (1 - scalePow) / (1 - s);
            double terminalVy = -g * s / (1.0 - s);
            vy = Math.max(terminalVy, Math.min(seedY, vy));
        }
        return new Vec(hx, vy, hz);
    }

    private static Vec clamp(Vec v, double cx, double cy, double cz) {
        return new Vec(
                Math.abs(v.x()) < cx ? 0.0 : v.x(),
                Math.abs(v.y()) < cy ? 0.0 : v.y(),
                Math.abs(v.z()) < cz ? 0.0 : v.z());
    }
}
