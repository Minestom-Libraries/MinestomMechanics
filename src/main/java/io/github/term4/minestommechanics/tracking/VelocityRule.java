package io.github.term4.minestommechanics.tracking;

import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.PhysicsUtils;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;

import java.util.function.Function;

/**
 * Strategy for estimating an entity's velocity (b/t) for the knockback friction term. A single-method interface
 * with a {@link #DEFAULT} and static factories, mirroring {@code AttackEvent.CriticalRule}. Two base rules,
 * mixable per axis with {@link #split}:
 * <ul>
 *   <li>{@link #simulated(VelocityConfig)} - the server-tracked velocity (vanilla {@code this.motX/motY/motZ}):
 *       vertical from {@link MotionTracker}'s ticked motY sim, horizontal from its impulse residual + entity
 *       push, gravity + drag read live from the entity.</li>
 *   <li>{@link #delta()} - trust the client's reported motion (position delta, which includes knockback).</li>
 * </ul>
 */
@FunctionalInterface
public interface VelocityRule {

    /** How the {@link #simulated(VelocityConfig) simulated} vertical is evaluated. */
    enum ArcStyle {
        /**
         * The live ticked motY sim ({@link MotionTracker#serverMotY}: vanilla {@code m()}'s clamp -&gt;
         * {@code move()} collide-zero -&gt; gravity), carrying the emergent quirks (early-collide re-falls) a
         * reconstruction cannot - vanilla-faithful 1.8. Non-players (no sim) step the reconstructed arc per tick
         * via {@link PhysicsUtils#updateVelocity} instead.
         */
        PER_TICK,
        /** Analytic {@code v0*s^t - g*s*(1 - s^t)/(1 - s)} (no sim/loop); smooths through the apex - cheaper. */
        CLOSED
    }

    /** Estimated velocity (b/t) for the knockback friction term. */
    Vec estimate(VelocityContext ctx);

    /** Rule used when a config does not specify one (the default {@link #simulated()} arc). */
    VelocityRule DEFAULT = simulated();

    /** Position delta (b/t) - players: the client's reported motion; non-players: their server velocity. Unclamped. */
    static VelocityRule delta() { return VelocityContext::positionDelta; }

    /** Server-tracked velocity with vanilla-default knobs. */
    static VelocityRule simulated() { return simulated(VelocityConfig.defaults()); }

    /** Server-tracked velocity with the given {@link VelocityConfig}. */
    static VelocityRule simulated(VelocityConfig cfg) { return ctx -> arc(ctx, cfg); }

    /**
     * Reconstructed launch arc with per-context knobs (e.g. a ping-scaled {@code groundTicks}). Preferred over
     * a config-level rule lambda when only arc knobs vary: a bare lambda there is ambiguous, since
     * {@link VelocityRule} is itself functional.
     */
    static VelocityRule simulated(Function<VelocityContext, VelocityConfig> cfg) {
        return ctx -> arc(ctx, cfg.apply(ctx));
    }

    /** Mixes two rules per axis: horizontal (x/z) from {@code horizontal}, vertical (y) from {@code vertical}. */
    static VelocityRule split(VelocityRule horizontal, VelocityRule vertical) {
        return ctx -> {
            Vec h = horizontal.estimate(ctx);
            return new Vec(h.x(), vertical.estimate(ctx).y(), h.z());
        };
    }

    /**
     * The server-tracked velocity fold (vanilla {@code this.motX/motY/motZ}):
     * <ul>
     *   <li><b>Vertical</b> - {@link #verticalMot}: the ticked motY sim (PER_TICK players), the analytic curve
     *       (CLOSED), or the air-clock reconstruction (non-players / pre-sim).</li>
     *   <li><b>Horizontal</b> - {@link MotionTracker#horizontalMot}: the victim's own impulse residual (the
     *       {@code bF()} sprint-jump boost, friction-bled), folded in <em>every</em> ground state - NOT the
     *       knockback (vanilla restores the pre-KB velocity after broadcasting it) and not the player's running
     *       velocity. Why a chained sprint victim eases ~0.9 -&gt; ~0.86: the carried boost opposes the next hit.
     *       Plus the {@code Entity.collide} push residual when {@link VelocityConfig#entityPush()} is on.</li>
     * </ul>
     * Per-component clamps apply last, like vanilla clamping the combined mot.
     */
    private static Vec arc(VelocityContext ctx, VelocityConfig cfg) {
        // Non-players ARE server-simulated: their velocity field is the server-tracked velocity, no
        // reconstruction needed (players are client-driven, hence the sim below).
        if (!(ctx.entity() instanceof Player)) {
            return clamp(ctx.positionDelta(), cfg.clampX(), cfg.clampY(), cfg.clampZ());
        }
        Vec hMot = MotionTracker.horizontalMot(ctx.entity(), cfg.launchOffset());
        Vec out = new Vec(hMot.x(), verticalMot(ctx, cfg), hMot.z());
        if (cfg.entityPush()) {
            Vec push = MotionTracker.entityPush(ctx.entity());
            out = out.add(push.x(), 0, push.z());
        }
        return clamp(out, cfg.clampX(), cfg.clampY(), cfg.clampZ());
    }

    /**
     * Vertical mot: PER_TICK reads the live ticked sim ({@link MotionTracker#serverMotY} - phase-correct at
     * {@link VelocityConfig#DEFAULT_LAUNCH_OFFSET}; offset deltas read into its history, a debug knob), else
     * falls through to {@link #reconstructedVy}.
     */
    private static double verticalMot(VelocityContext ctx, VelocityConfig cfg) {
        if (cfg.verticalStyle() == ArcStyle.PER_TICK) {
            Double simY = MotionTracker.serverMotY(ctx.entity(),
                    VelocityConfig.DEFAULT_LAUNCH_OFFSET - cfg.launchOffset(), cfg.clampY() > 0);
            if (simY != null) return simY;
        }
        return reconstructedVy(ctx, cfg);
    }

    /**
     * Fallback vertical reconstruction from the air clock (CLOSED style, non-players, or before the sim has
     * ticked): seeds {@code cfg.seed()} at the launch anchor, advances {@code ticksInAir + launchOffset} ticks
     * (a walk-off seeds from rest at {@code ticksInAir + 1}), gated on {@link MotionTracker#onGround} with
     * {@code groundTicks}; {@code maxAirTicks} caps a long juggle's clock.
     */
    private static double reconstructedVy(VelocityContext ctx, VelocityConfig cfg) {
        boolean grounded = ctx.onGround(cfg.groundTicks());
        boolean launched = !grounded && ctx.launched();
        int air = grounded ? 0 : ctx.ticksInAir();
        if (cfg.maxAirTicks() != null) air = Math.min(air, cfg.maxAirTicks());
        int ticks = launched ? air + cfg.launchOffset() : air + 1;
        double seedY = launched ? cfg.seed() : 0;
        Aerodynamics aero = ctx.entity().getAerodynamics();
        return cfg.verticalStyle() == ArcStyle.CLOSED
                ? closedVy(aero, cfg.seed(), seedY, ticks)
                : steppedVy(ctx.entity(), aero, cfg.clampY(), seedY, ticks);
    }

    /**
     * Advances {@code seedY} by {@code ticks} airborne ticks via {@link PhysicsUtils#updateVelocity} (gravity +
     * air resistance, {@code onGround=false}), zeroing it below {@code clampY} before each step (the apex
     * reseed). The block getter is unused while airborne, so a null instance is harmless.
     */
    private static double steppedVy(Entity entity, Aerodynamics aero, double clampY, double seedY, int ticks) {
        if (ticks <= 0) return seedY;
        Vec vel = new Vec(0, seedY, 0);
        var pos = entity.getPosition();
        var blocks = entity.getInstance();
        for (int t = 0; t < ticks; t++) {
            if (Math.abs(vel.y()) < clampY) vel = vel.withY(0);
            vel = PhysicsUtils.updateVelocity(pos, vel, blocks, aero, true, false, false, false);
        }
        return vel.y();
    }

    /**
     * Analytic vertical: {@code v0*s^t - g*s*(1 - s^t)/(1 - s)}, clamped to {@code [terminalVy, seedY]}. No apex
     * reseed (~0.003 b/t shallow per descending tick vs {@link #steppedVy}). {@code ticks <= 0} returns the
     * launch-tick {@code -g*s}.
     */
    private static double closedVy(Aerodynamics aero, double seedY, double v0, int ticks) {
        double g = aero.gravity();
        double s = aero.verticalAirResistance();
        if (ticks <= 0) return -g * s;
        double scalePow = Math.pow(s, ticks);
        double vy = v0 * scalePow - g * s * (1 - scalePow) / (1 - s);
        double terminalVy = -g * s / (1.0 - s);
        return Math.max(terminalVy, Math.min(seedY, vy));
    }

    private static Vec clamp(Vec v, double cx, double cy, double cz) {
        return new Vec(
                Math.abs(v.x()) < cx ? 0.0 : v.x(),
                Math.abs(v.y()) < cy ? 0.0 : v.y(),
                Math.abs(v.z()) < cz ? 0.0 : v.z());
    }
}
