package io.github.term4.minestommechanics.tracking;

import org.jetbrains.annotations.Nullable;

/**
 * Knobs for the {@link VelocityRule#simulated(VelocityConfig) simulated} server-tracked velocity. Plain values:
 * per-context conditionality lives one level up ({@code KnockbackConfig.velocity} accepts a context lambda, and
 * {@link VelocityRule#simulated(java.util.function.Function)} accepts per-context knobs). Gravity, air drag, and
 * ground friction are not configured here - they are read live from the entity's
 * {@link net.minestom.server.collision.Aerodynamics} and the block under it. Build with {@link #builder()}.
 *
 * @param seed            vertical takeoff velocity (b/t) seeding the <em>fallback</em> reconstruction (vanilla
 *                        {@link #JUMP_VELOCITY}); the ticked motY sim always seeds {@link #JUMP_VELOCITY}, like
 *                        vanilla's {@code PlayerConnection} jump detection calling {@code bF()}.
 * @param horizontalStyle currently inert: the horizontal always folds {@link MotionTracker#horizontalMot} (the
 *                        impulse residual - pure exponential decay, identical either style). Kept for config
 *                        stability.
 * @param verticalStyle   how the vertical is evaluated (ticked sim vs analytic curve).
 * @param launchOffset    arc phase correction ({@link #DEFAULT_LAUNCH_OFFSET}). The ticked sim is phase-correct
 *                        at the default; deltas read into its history (debug knob, may go away).
 * @param clampX          near-zero clamp for the folded x component (vanilla {@link #CLAMP}).
 * @param clampY          near-zero clamp for motY - selects the sim's apex-reseed variant and drives the
 *                        fallback's per-step reseed; {@code 0} disables (e.g. Hypixel's calibration).
 * @param clampZ          near-zero clamp for the folded z component.
 * @param groundTicks     fall-prediction depth for {@link MotionTracker#onGround} (used by the fallback's gate
 *                        and external reads): {@code 0} = raw client flag, {@code >= 1} = the flag OR the sim's
 *                        {@code move()} collision OR a {@code ticks}-deep forward probe of the sim's motY
 *                        (leniency for laggy landings).
 * @param maxAirTicks     fallback-only: caps the reconstruction's air clock so a long juggle cannot fold values
 *                        past the end of the gravity curve; {@code null} = unbounded (the ticked sim is naturally
 *                        bounded by terminal velocity).
 * @param entityPush      fold {@link MotionTracker#entityPush} (the server-side {@code Entity.collide} residual
 *                        from overlapping entities) into the horizontal estimate - vanilla {@code motX/motZ}
 *                        carries it, so both the melee friction fold and the hurt broadcast shove an overlapped
 *                        victim. Disable on servers (e.g. Hypixel) that turn player collision off.
 */
public record VelocityConfig(
        double seed,
        VelocityRule.ArcStyle horizontalStyle,
        VelocityRule.ArcStyle verticalStyle,
        int launchOffset,
        double clampX,
        double clampY,
        double clampZ,
        int groundTicks,
        @Nullable Integer maxAirTicks,
        boolean entityPush
) {

    /** Vanilla gravity (b/t^2): the {@code motY -= 0.08} step. */
    public static final double GRAVITY = 0.08;
    /** Vanilla vertical air drag: the {@code motY *= 0.98} step. */
    public static final double DRAG_V = 0.98;
    /** Vanilla horizontal air drag ({@code motX/motZ *= 0.91} airborne). */
    public static final double DRAG_H = 0.91;
    /**
     * Vanilla jump takeoff velocity ({@code bF()} {@code 0.42F} widened to double); the default {@link #seed}.
     * Float-exact on purpose: the hurt broadcast sends the bare seed at air-tick 1 (wire short {@code 3359},
     * not {@code 3360}) - the melee fold never exposes the difference because its t1 value caps at 0.4.
     */
    public static final double JUMP_VELOCITY = 0.41999998688697815;
    /** Vanilla near-zero motion clamp ({@code m()} zeroes {@code |mot| < 0.005} each tick). */
    public static final double CLAMP = 0.005;

    /**
     * Air-tick phase correction for the reconstructed arc ({@code -1}) - <strong>not</strong> a gravity lag.
     * {@link MotionTracker#ticksInAir} runs on the server clock, but the attacker's hit packet is processed
     * before the victim's move packet that same tick, so the victim's freshest processed motion is one tick
     * behind the clock; {@code arc(ticksInAir - 1)} reproduces the last-reported {@code motY} the fold consumes
     * (vanilla folds {@code this.motY} as-is and never re-simulates it - see 1.8 {@code EntityHuman.attack}).
     * Pure phase, so it is identical for {@link VelocityRule.ArcStyle#CLOSED} and
     * {@link VelocityRule.ArcStyle#PER_TICK}, and calibrated 1:1 against both vanilla 1.8 and the Hypixel
     * vertical-KB sheet.
     */
    public static final int DEFAULT_LAUNCH_OFFSET = -1;

    /** Vanilla defaults: {@code 0.42} seed, PER_TICK both axes, offset {@code -1}, {@code 0.005} clamps, 1-tick ground sweep, unbounded air clock, entity push folded. */
    public static VelocityConfig defaults() { return builder().build(); }

    public Builder toBuilder() { return new Builder(this); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private double seed = JUMP_VELOCITY;
        private VelocityRule.ArcStyle horizontalStyle = VelocityRule.ArcStyle.PER_TICK;
        private VelocityRule.ArcStyle verticalStyle = VelocityRule.ArcStyle.PER_TICK;
        private int launchOffset = DEFAULT_LAUNCH_OFFSET;
        private double clampX = CLAMP;
        private double clampY = CLAMP;
        private double clampZ = CLAMP;
        private int groundTicks = 1;
        private @Nullable Integer maxAirTicks;
        private boolean entityPush = true;

        Builder() {}

        Builder(VelocityConfig c) {
            seed = c.seed;
            horizontalStyle = c.horizontalStyle;
            verticalStyle = c.verticalStyle;
            launchOffset = c.launchOffset;
            clampX = c.clampX;
            clampY = c.clampY;
            clampZ = c.clampZ;
            groundTicks = c.groundTicks;
            maxAirTicks = c.maxAirTicks;
            entityPush = c.entityPush;
        }

        public Builder seed(double v) { seed = v; return this; }
        public Builder horizontalStyle(VelocityRule.ArcStyle v) { horizontalStyle = v; return this; }
        public Builder verticalStyle(VelocityRule.ArcStyle v) { verticalStyle = v; return this; }
        public Builder style(VelocityRule.ArcStyle both) { horizontalStyle = both; verticalStyle = both; return this; }
        public Builder launchOffset(int v) { launchOffset = v; return this; }
        public Builder clamp(double all) { clampX = all; clampY = all; clampZ = all; return this; }
        public Builder clampX(double v) { clampX = v; return this; }
        public Builder clampY(double v) { clampY = v; return this; }
        public Builder clampZ(double v) { clampZ = v; return this; }
        public Builder groundTicks(int v) { groundTicks = v; return this; }
        public Builder maxAirTicks(@Nullable Integer v) { maxAirTicks = v; return this; }
        public Builder entityPush(boolean v) { entityPush = v; return this; }

        // TODO: fullHitScale(double) — when the attacker satisfies hitLanded && damageResulting > 0 &&
        // knockbackDealt, multiply their tracked horizontal velocity (X/Z) by this factor on that tick.
        // Vanilla 1.8/26.1: 0.6 (EntityHuman.attack / Player.causeExtraKnockback). Minemen: 1.0 (no scale).

        public VelocityConfig build() {
            return new VelocityConfig(seed, horizontalStyle, verticalStyle, launchOffset,
                    clampX, clampY, clampZ, groundTicks, maxAirTicks, entityPush);
        }
    }
}
