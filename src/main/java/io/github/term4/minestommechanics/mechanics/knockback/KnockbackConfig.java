package io.github.term4.minestommechanics.mechanics.knockback;

import io.github.term4.minestommechanics.config.Config;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfigResolver.KnockbackContext;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Immutable knockback config. Use {@link #builder()}, {@link #toBuilder()}. */
public final class KnockbackConfig extends Config<KnockbackContext, KnockbackConfig> {

    public enum DirectionMode { SCALAR, VECTOR_ADDITION }
    /** How a friction value is applied to the reconstructed victim velocity in the friction term. */
    public enum FrictionMode {
        /** {@code mot / value} - the value is a divisor (default; vanilla-style). */
        DIVISOR,
        /** {@code mot * value} - the value is a multiplier, so coefficients (incl. negatives) read directly. */
        FACTOR
    }

    /** Lower and upper bound for knockback components. Null means no bound. */
    public record Bounds(@Nullable Double lower, @Nullable Double upper) {
        public static Bounds of(@Nullable Double lower, @Nullable Double upper) {
            return new Bounds(lower, upper);
        }
    }

    public final FieldValue<KnockbackContext, Integer> sprintBuffer;
    public final FieldValue<KnockbackContext, Double> horizontal;
    public final FieldValue<KnockbackContext, Double> vertical;
    public final FieldValue<KnockbackContext, Double> extraHorizontal;
    public final FieldValue<KnockbackContext, Double> extraVertical;
    public final FieldValue<KnockbackContext, Bounds> horizontalBounds;
    public final FieldValue<KnockbackContext, Bounds> verticalBounds;
    public final FieldValue<KnockbackContext, Bounds> extraHorizontalBounds;
    public final FieldValue<KnockbackContext, Bounds> extraVerticalBounds;
    public final FieldValue<KnockbackContext, Double> yawWeight;
    public final FieldValue<KnockbackContext, Double> extraYawWeight;
    public final FieldValue<KnockbackContext, Double> pitchWeight;
    public final FieldValue<KnockbackContext, Double> extraPitchWeight;
    public final FieldValue<KnockbackContext, Double> heightDelta;
    public final FieldValue<KnockbackContext, Double> extraHeightDelta;
    public final FieldValue<KnockbackContext, DirectionMode> horizontalCombine;
    public final FieldValue<KnockbackContext, DirectionMode> verticalCombine;
    public final FieldValue<KnockbackContext, Double> frictionH;
    public final FieldValue<KnockbackContext, Double> frictionV;
    public final FieldValue<KnockbackContext, FrictionMode> frictionModeH;
    public final FieldValue<KnockbackContext, FrictionMode> frictionModeV;
    /**
     * How the friction term estimates the victim's velocity (see {@link VelocityRule}). Resolution is
     * config override -&gt; the victim's scoped {@link io.github.term4.minestommechanics.MechanicsProfile#velocity()
     * profile velocity} -&gt; {@link VelocityRule#DEFAULT}, so prefer setting the tracking method ONCE on a profile
     * scope and leaving this unset; use this only for a genuine per-config override (e.g. a preset with a custom
     * fold). Custom {@link KnockbackComponent}s read the resolved rule via {@code ctx.victimVelocity()}.
     */
    public final FieldValue<KnockbackContext, VelocityRule> velocity;
    /**
     * Quantize the outgoing velocity to 1.8's wire grid (truncated {@code x8000} shorts - see
     * {@link io.github.term4.minestommechanics.util.LegacyVelocity}). This is a <em>server-emulation</em>
     * knob, not a client one: a server emulating 1.8 sends what a 1.8 server would have sent, to ALL clients
     * (the modern encoding carries near-raw doubles, so without this a boundary value like the {@code 0.42F}
     * jump seed arrives one short high). Unset = enabled; disable for modern-mechanics presets.
     */
    public final FieldValue<KnockbackContext, Boolean> quantizeVelocity;
    /**
     * Pluggable transforms applied in order on the <em>final</em> knockback vector (blocks/tick), after all
     * base/extra/friction/bounds logic. Each {@link KnockbackComponent} self-gates and may apply non-linear
     * logic the base pipeline cannot (e.g. axis snapping, distance limits). {@code null} or empty means none.
     * TODO(stages): components are post-stages only; replacing a BUILT-IN stage's formula (friction fold,
     *  combine, bounds) will become per-stage strategy knobs - see the KnockbackCalculator stages TODO.
     */
    @Nullable public final List<KnockbackComponent> customComponents;

    private KnockbackConfig(Builder b) {
        super(b.subConfig);
        sprintBuffer = b.sprintBuffer;
        horizontal = b.horizontal;
        vertical = b.vertical;
        extraHorizontal = b.extraHorizontal;
        extraVertical = b.extraVertical;
        horizontalBounds = b.horizontalBounds;
        verticalBounds = b.verticalBounds;
        extraHorizontalBounds = b.extraHorizontalBounds;
        extraVerticalBounds = b.extraVerticalBounds;
        yawWeight = b.yawWeight;
        extraYawWeight = b.extraYawWeight;
        pitchWeight = b.pitchWeight;
        extraPitchWeight = b.extraPitchWeight;
        heightDelta = b.heightDelta;
        extraHeightDelta = b.extraHeightDelta;
        horizontalCombine = b.horizontalCombine;
        verticalCombine = b.verticalCombine;
        frictionH = b.frictionH;
        frictionV = b.frictionV;
        frictionModeH = b.frictionModeH;
        frictionModeV = b.frictionModeV;
        velocity = b.velocity;
        quantizeVelocity = b.quantizeVelocity;
        customComponents = b.customComponents;
    }

    /** Merges this config over base. */
    public KnockbackConfig fromBase(KnockbackConfig base) {
        return new Builder()
                .subConfig(subConfig != null ? subConfig : base.subConfig)
                .sprintBuffer(merge(sprintBuffer, base.sprintBuffer))
                .horizontal(merge(horizontal, base.horizontal))
                .vertical(merge(vertical, base.vertical))
                .extraHorizontal(merge(extraHorizontal, base.extraHorizontal))
                .extraVertical(merge(extraVertical, base.extraVertical))
                .horizontalBounds(merge(horizontalBounds, base.horizontalBounds))
                .verticalBounds(merge(verticalBounds, base.verticalBounds))
                .extraHorizontalBounds(merge(extraHorizontalBounds, base.extraHorizontalBounds))
                .extraVerticalBounds(merge(extraVerticalBounds, base.extraVerticalBounds))
                .yawWeight(merge(yawWeight, base.yawWeight))
                .extraYawWeight(merge(extraYawWeight, base.extraYawWeight))
                .pitchWeight(merge(pitchWeight, base.pitchWeight))
                .extraPitchWeight(merge(extraPitchWeight, base.extraPitchWeight))
                .heightDelta(merge(heightDelta, base.heightDelta))
                .extraHeightDelta(merge(extraHeightDelta, base.extraHeightDelta))
                .horizontalCombine(merge(horizontalCombine, base.horizontalCombine))
                .verticalCombine(merge(verticalCombine, base.verticalCombine))
                .frictionH(merge(frictionH, base.frictionH))
                .frictionV(merge(frictionV, base.frictionV))
                .frictionModeH(merge(frictionModeH, base.frictionModeH))
                .frictionModeV(merge(frictionModeV, base.frictionModeV))
                .velocity(merge(velocity, base.velocity))
                .quantizeVelocity(merge(quantizeVelocity, base.quantizeVelocity))
                .customComponents(customComponents != null ? customComponents : base.customComponents)
                .build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return builder(null);
    }

    public static Builder builder(@Nullable KnockbackConfig base) {
        return base != null ? new Builder(base) : new Builder();
    }

    public static final class Builder {
        private Function<KnockbackContext, KnockbackConfig> subConfig;
        private FieldValue<KnockbackContext, Integer> sprintBuffer;
        private FieldValue<KnockbackContext, Double> horizontal;
        private FieldValue<KnockbackContext, Double> vertical;
        private FieldValue<KnockbackContext, Double> extraHorizontal;
        private FieldValue<KnockbackContext, Double> extraVertical;
        private FieldValue<KnockbackContext, Bounds> horizontalBounds;
        private FieldValue<KnockbackContext, Bounds> verticalBounds;
        private FieldValue<KnockbackContext, Bounds> extraHorizontalBounds;
        private FieldValue<KnockbackContext, Bounds> extraVerticalBounds;
        private FieldValue<KnockbackContext, Double> yawWeight;
        private FieldValue<KnockbackContext, Double> extraYawWeight;
        private FieldValue<KnockbackContext, Double> pitchWeight;
        private FieldValue<KnockbackContext, Double> extraPitchWeight;
        private FieldValue<KnockbackContext, Double> heightDelta;
        private FieldValue<KnockbackContext, Double> extraHeightDelta;
        private FieldValue<KnockbackContext, DirectionMode> horizontalCombine;
        private FieldValue<KnockbackContext, DirectionMode> verticalCombine;
        private FieldValue<KnockbackContext, Double> frictionH;
        private FieldValue<KnockbackContext, Double> frictionV;
        private FieldValue<KnockbackContext, FrictionMode> frictionModeH;
        private FieldValue<KnockbackContext, FrictionMode> frictionModeV;
        private FieldValue<KnockbackContext, VelocityRule> velocity;
        private FieldValue<KnockbackContext, Boolean> quantizeVelocity;
        private List<KnockbackComponent> customComponents;

        Builder() {}

        Builder(KnockbackConfig c) {
            subConfig = c.subConfig;
            sprintBuffer = c.sprintBuffer;
            horizontal = c.horizontal;
            vertical = c.vertical;
            extraHorizontal = c.extraHorizontal;
            extraVertical = c.extraVertical;
            horizontalBounds = c.horizontalBounds;
            verticalBounds = c.verticalBounds;
            extraHorizontalBounds = c.extraHorizontalBounds;
            extraVerticalBounds = c.extraVerticalBounds;
            yawWeight = c.yawWeight;
            extraYawWeight = c.extraYawWeight;
            pitchWeight = c.pitchWeight;
            extraPitchWeight = c.extraPitchWeight;
            heightDelta = c.heightDelta;
            extraHeightDelta = c.extraHeightDelta;
            horizontalCombine = c.horizontalCombine;
            verticalCombine = c.verticalCombine;
            frictionH = c.frictionH;
            frictionV = c.frictionV;
            frictionModeH = c.frictionModeH;
            frictionModeV = c.frictionModeV;
            velocity = c.velocity;
            quantizeVelocity = c.quantizeVelocity;
            customComponents = c.customComponents;
        }

        public Builder subConfig(Function<KnockbackContext, KnockbackConfig> fn) { subConfig = fn; return this; }
        public Builder sprintBuffer(Integer v) { sprintBuffer = FieldValue.constant(v); return this; }
        public Builder sprintBuffer(Function<KnockbackContext, Integer> fn) { sprintBuffer = FieldValue.of(fn); return this; }
        public Builder sprintBuffer(Integer fallback, Function<KnockbackContext, Integer> fn) { sprintBuffer = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder horizontal(Double v) { horizontal = FieldValue.constant(v); return this; }
        public Builder horizontal(Function<KnockbackContext, Double> fn) { horizontal = FieldValue.of(fn); return this; }
        public Builder horizontal(Double fallback, Function<KnockbackContext, Double> fn) { horizontal = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder vertical(Double v) { vertical = FieldValue.constant(v); return this; }
        public Builder vertical(Function<KnockbackContext, Double> fn) { vertical = FieldValue.of(fn); return this; }
        public Builder vertical(Double fallback, Function<KnockbackContext, Double> fn) { vertical = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraHorizontal(Double v) { extraHorizontal = FieldValue.constant(v); return this; }
        public Builder extraHorizontal(Function<KnockbackContext, Double> fn) { extraHorizontal = FieldValue.of(fn); return this; }
        public Builder extraHorizontal(Double fallback, Function<KnockbackContext, Double> fn) { extraHorizontal = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraVertical(Double v) { extraVertical = FieldValue.constant(v); return this; }
        public Builder extraVertical(Function<KnockbackContext, Double> fn) { extraVertical = FieldValue.of(fn); return this; }
        public Builder extraVertical(Double fallback, Function<KnockbackContext, Double> fn) { extraVertical = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder horizontalBounds(@Nullable Double lower, @Nullable Double upper) { horizontalBounds = FieldValue.constant(Bounds.of(lower, upper)); return this; }
        public Builder horizontalBounds(Bounds v) { horizontalBounds = FieldValue.constant(v); return this; }
        public Builder horizontalBounds(Function<KnockbackContext, Bounds> fn) { horizontalBounds = FieldValue.of(fn); return this; }
        public Builder horizontalBounds(Bounds fallback, Function<KnockbackContext, Bounds> fn) { horizontalBounds = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder verticalBounds(@Nullable Double lower, @Nullable Double upper) { verticalBounds = FieldValue.constant(Bounds.of(lower, upper)); return this; }
        public Builder verticalBounds(Bounds v) { verticalBounds = FieldValue.constant(v); return this; }
        public Builder verticalBounds(Function<KnockbackContext, Bounds> fn) { verticalBounds = FieldValue.of(fn); return this; }
        public Builder verticalBounds(Bounds fallback, Function<KnockbackContext, Bounds> fn) { verticalBounds = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraHorizontalBounds(@Nullable Double lower, @Nullable Double upper) { extraHorizontalBounds = FieldValue.constant(Bounds.of(lower, upper)); return this; }
        public Builder extraHorizontalBounds(Bounds v) { extraHorizontalBounds = FieldValue.constant(v); return this; }
        public Builder extraHorizontalBounds(Function<KnockbackContext, Bounds> fn) { extraHorizontalBounds = FieldValue.of(fn); return this; }
        public Builder extraHorizontalBounds(Bounds fallback, Function<KnockbackContext, Bounds> fn) { extraHorizontalBounds = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraVerticalBounds(@Nullable Double lower, @Nullable Double upper) { extraVerticalBounds = FieldValue.constant(Bounds.of(lower, upper)); return this; }
        public Builder extraVerticalBounds(Bounds v) { extraVerticalBounds = FieldValue.constant(v); return this; }
        public Builder extraVerticalBounds(Function<KnockbackContext, Bounds> fn) { extraVerticalBounds = FieldValue.of(fn); return this; }
        public Builder extraVerticalBounds(Bounds fallback, Function<KnockbackContext, Bounds> fn) { extraVerticalBounds = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder yawWeight(Double v) { yawWeight = FieldValue.constant(v); return this; }
        public Builder yawWeight(Function<KnockbackContext, Double> fn) { yawWeight = FieldValue.of(fn); return this; }
        public Builder yawWeight(Double fallback, Function<KnockbackContext, Double> fn) { yawWeight = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraYawWeight(Double v) { extraYawWeight = FieldValue.constant(v); return this; }
        public Builder extraYawWeight(Function<KnockbackContext, Double> fn) { extraYawWeight = FieldValue.of(fn); return this; }
        public Builder extraYawWeight(Double fallback, Function<KnockbackContext, Double> fn) { extraYawWeight = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder pitchWeight(Double v) { pitchWeight = FieldValue.constant(v); return this; }
        public Builder pitchWeight(Function<KnockbackContext, Double> fn) { pitchWeight = FieldValue.of(fn); return this; }
        public Builder pitchWeight(Double fallback, Function<KnockbackContext, Double> fn) { pitchWeight = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraPitchWeight(Double v) { extraPitchWeight = FieldValue.constant(v); return this; }
        public Builder extraPitchWeight(Function<KnockbackContext, Double> fn) { extraPitchWeight = FieldValue.of(fn); return this; }
        public Builder extraPitchWeight(Double fallback, Function<KnockbackContext, Double> fn) { extraPitchWeight = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder heightDelta(Double v) { heightDelta = FieldValue.constant(v); return this; }
        public Builder heightDelta(Function<KnockbackContext, Double> fn) { heightDelta = FieldValue.of(fn); return this; }
        public Builder heightDelta(Double fallback, Function<KnockbackContext, Double> fn) { heightDelta = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder extraHeightDelta(Double v) { extraHeightDelta = FieldValue.constant(v); return this; }
        public Builder extraHeightDelta(Function<KnockbackContext, Double> fn) { extraHeightDelta = FieldValue.of(fn); return this; }
        public Builder extraHeightDelta(Double fallback, Function<KnockbackContext, Double> fn) { extraHeightDelta = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder horizontalCombine(DirectionMode v) { horizontalCombine = FieldValue.constant(v); return this; }
        public Builder horizontalCombine(Function<KnockbackContext, DirectionMode> fn) { horizontalCombine = FieldValue.of(fn); return this; }
        public Builder horizontalCombine(DirectionMode fallback, Function<KnockbackContext, DirectionMode> fn) { horizontalCombine = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder verticalCombine(DirectionMode v) { verticalCombine = FieldValue.constant(v); return this; }
        public Builder verticalCombine(Function<KnockbackContext, DirectionMode> fn) { verticalCombine = FieldValue.of(fn); return this; }
        public Builder verticalCombine(DirectionMode fallback, Function<KnockbackContext, DirectionMode> fn) { verticalCombine = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder frictionH(Double v) { frictionH = FieldValue.constant(v); return this; }
        public Builder frictionH(Function<KnockbackContext, Double> fn) { frictionH = FieldValue.of(fn); return this; }
        public Builder frictionH(Double fallback, Function<KnockbackContext, Double> fn) { frictionH = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder frictionV(Double v) { frictionV = FieldValue.constant(v); return this; }
        public Builder frictionV(Function<KnockbackContext, Double> fn) { frictionV = FieldValue.of(fn); return this; }
        public Builder frictionV(Double fallback, Function<KnockbackContext, Double> fn) { frictionV = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder frictionModeH(FrictionMode v) { frictionModeH = FieldValue.constant(v); return this; }
        public Builder frictionModeH(Function<KnockbackContext, FrictionMode> fn) { frictionModeH = FieldValue.of(fn); return this; }
        public Builder frictionModeH(FrictionMode fallback, Function<KnockbackContext, FrictionMode> fn) { frictionModeH = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder frictionModeV(FrictionMode v) { frictionModeV = FieldValue.constant(v); return this; }
        public Builder frictionModeV(Function<KnockbackContext, FrictionMode> fn) { frictionModeV = FieldValue.of(fn); return this; }
        public Builder frictionModeV(FrictionMode fallback, Function<KnockbackContext, FrictionMode> fn) { frictionModeV = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder velocity(VelocityRule v) { velocity = FieldValue.constant(v); return this; }
        public Builder velocity(Function<KnockbackContext, VelocityRule> fn) { velocity = FieldValue.of(fn); return this; }
        public Builder velocity(VelocityRule fallback, Function<KnockbackContext, VelocityRule> fn) { velocity = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder quantizeVelocity(Boolean v) { quantizeVelocity = FieldValue.constant(v); return this; }
        public Builder quantizeVelocity(Function<KnockbackContext, Boolean> fn) { quantizeVelocity = FieldValue.of(fn); return this; }
        public Builder quantizeVelocity(Boolean fallback, Function<KnockbackContext, Boolean> fn) { quantizeVelocity = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Appends to the current/inherited components (copying first, so shared lists aren't mutated). */
        public Builder addCustomComponent(KnockbackComponent component) {
            List<KnockbackComponent> list = customComponents == null ? new ArrayList<>() : new ArrayList<>(customComponents);
            list.add(component);
            customComponents = list;
            return this;
        }
        Builder sprintBuffer(FieldValue<KnockbackContext, Integer> v) { sprintBuffer = v; return this; }
        Builder horizontal(FieldValue<KnockbackContext, Double> v) { horizontal = v; return this; }
        Builder vertical(FieldValue<KnockbackContext, Double> v) { vertical = v; return this; }
        Builder extraHorizontal(FieldValue<KnockbackContext, Double> v) { extraHorizontal = v; return this; }
        Builder extraVertical(FieldValue<KnockbackContext, Double> v) { extraVertical = v; return this; }
        Builder horizontalBounds(FieldValue<KnockbackContext, Bounds> v) { horizontalBounds = v; return this; }
        Builder verticalBounds(FieldValue<KnockbackContext, Bounds> v) { verticalBounds = v; return this; }
        Builder extraHorizontalBounds(FieldValue<KnockbackContext, Bounds> v) { extraHorizontalBounds = v; return this; }
        Builder extraVerticalBounds(FieldValue<KnockbackContext, Bounds> v) { extraVerticalBounds = v; return this; }
        Builder yawWeight(FieldValue<KnockbackContext, Double> v) { yawWeight = v; return this; }
        Builder extraYawWeight(FieldValue<KnockbackContext, Double> v) { extraYawWeight = v; return this; }
        Builder pitchWeight(FieldValue<KnockbackContext, Double> v) { pitchWeight = v; return this; }
        Builder extraPitchWeight(FieldValue<KnockbackContext, Double> v) { extraPitchWeight = v; return this; }
        Builder heightDelta(FieldValue<KnockbackContext, Double> v) { heightDelta = v; return this; }
        Builder extraHeightDelta(FieldValue<KnockbackContext, Double> v) { extraHeightDelta = v; return this; }
        Builder horizontalCombine(FieldValue<KnockbackContext, DirectionMode> v) { horizontalCombine = v; return this; }
        Builder verticalCombine(FieldValue<KnockbackContext, DirectionMode> v) { verticalCombine = v; return this; }
        Builder frictionH(FieldValue<KnockbackContext, Double> v) { frictionH = v; return this; }
        Builder frictionV(FieldValue<KnockbackContext, Double> v) { frictionV = v; return this; }
        Builder frictionModeH(FieldValue<KnockbackContext, FrictionMode> v) { frictionModeH = v; return this; }
        Builder frictionModeV(FieldValue<KnockbackContext, FrictionMode> v) { frictionModeV = v; return this; }
        Builder velocity(FieldValue<KnockbackContext, VelocityRule> v) { velocity = v; return this; }
        Builder quantizeVelocity(FieldValue<KnockbackContext, Boolean> v) { quantizeVelocity = v; return this; }
        Builder customComponents(List<KnockbackComponent> v) { customComponents = v; return this; }

        public KnockbackConfig build() {
            return new KnockbackConfig(this);
        }
    }
}
