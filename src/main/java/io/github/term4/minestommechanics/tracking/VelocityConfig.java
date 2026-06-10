package io.github.term4.minestommechanics.tracking;

import org.jetbrains.annotations.Nullable;

/**
 * Knobs for the {@link VelocityRule#simulated(VelocityConfig) simulated} arc. Plain values: per-context
 * conditionality lives one level up (e.g. {@code KnockbackConfig.velocity} accepts a context lambda choosing a
 * whole {@link VelocityRule}). Gravity, air drag, and ground friction are not configured here - the arc reads
 * them live from the entity's {@link net.minestom.server.collision.Aerodynamics} and the block under it. Build
 * with {@link #builder()}.
 *
 * @param seed            vertical takeoff velocity (b/t) seeding a launch arc (vanilla {@link #JUMP_VELOCITY}).
 * @param horizontalStyle how the x/z arc is evaluated.
 * @param verticalStyle   how the y arc is evaluated.
 * @param launchOffset    air-tick phase correction ({@link #DEFAULT_LAUNCH_OFFSET}).
 * @param clampX          near-zero clamp for the folded x component (vanilla {@link #CLAMP}).
 * @param clampY          near-zero clamp for motY - also drives the PER_TICK apex reseed; {@code 0} disables.
 * @param clampZ          near-zero clamp for the folded z component.
 * @param groundTicks     fall-prediction depth for the {@link MotionTracker#onGround} gate on the arc's air
 *                        clock: {@code 0} = raw client flag, {@code 1} = vanilla {@code move()} collision sweep.
 * @param maxAirTicks     cap on the arc's effective air-tick so a long juggle cannot fold values past the end of
 *                        the gravity curve; {@code null} = unbounded.
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
        @Nullable Integer maxAirTicks
) {

    /** Vanilla gravity (b/t^2): the {@code motY -= 0.08} step. */
    public static final double GRAVITY = 0.08;
    /** Vanilla vertical air drag: the {@code motY *= 0.98} step. */
    public static final double DRAG_V = 0.98;
    /** Vanilla horizontal air drag ({@code motX/motZ *= 0.91} airborne). */
    public static final double DRAG_H = 0.91;
    /** Vanilla jump takeoff velocity ({@code bF()} {@code 0.42}); the default {@link #seed}. */
    public static final double JUMP_VELOCITY = 0.42;
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

    /** Vanilla defaults: {@code 0.42} seed, PER_TICK both axes, offset {@code -1}, {@code 0.005} clamps, 1-tick ground sweep, unbounded air clock. */
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

        public VelocityConfig build() {
            return new VelocityConfig(seed, horizontalStyle, verticalStyle, launchOffset,
                    clampX, clampY, clampZ, groundTicks, maxAirTicks);
        }
    }
}
