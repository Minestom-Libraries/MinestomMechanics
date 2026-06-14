package io.github.term4.minestommechanics.mechanics.damage.types.fall;

import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Config for {@link FallDamage}. Formula knobs are first-class fields; vanilla values are wired
 * in {@link io.github.term4.minestommechanics.mechanics.Vanilla18} / {@link io.github.term4.minestommechanics.mechanics.Vanilla}.
 * Users may override {@code baseAmount} with a lambda for fully custom behavior.
 */
public final class FallDamageConfig extends DamageTypeConfig {

    /** Which vanilla fall-damage formula to apply when {@code baseAmount} is not explicitly set. */
    public enum Formula {
        /** 1.8: {@code ceil(distance - threshold)}. */
        LEGACY_CEIL,
        /** 26.1+: {@code floor((distance + ε - threshold) * damageModifier * fallDamageMultiplier)}. */
        MODERN_FLOOR
    }

    private static final double MODERN_EPSILON = 1.0E-6;

    private final @Nullable FieldValue<DamageContext, Formula> formula;
    private final @Nullable FieldValue<DamageContext, Double> threshold;
    private final @Nullable FieldValue<DamageContext, Double> damageModifier;
    private final @Nullable FieldValue<DamageContext, Double> fallDamageMultiplier;

    private FallDamageConfig(Builder b) {
        super(b.common);
        this.formula = b.formula;
        this.threshold = b.threshold;
        this.damageModifier = b.damageModifier;
        this.fallDamageMultiplier = b.fallDamageMultiplier;
    }

    public @Nullable Formula formula(DamageContext ctx) { return resolve(formula, ctx); }

    /** Safe fall distance in blocks (maps to 1.8 threshold / modern {@code SAFE_FALL_DISTANCE}). */
    public @Nullable Double threshold(DamageContext ctx) { return resolve(threshold, ctx); }

    /**
     * Per-landing damage modifier (modern {@code causeFallDamage} {@code damageModifier}).
     * When the snapshot carries a {@link FallDetail#damageModifier()}, that wins over this default.
     */
    public @Nullable Double damageModifier(DamageContext ctx) { return resolve(damageModifier, ctx); }

    /** Modern {@code FALL_DAMAGE_MULTIPLIER} attribute (ignored by {@link Formula#LEGACY_CEIL}). */
    public @Nullable Double fallDamageMultiplier(DamageContext ctx) { return resolve(fallDamageMultiplier, ctx); }

    /**
     * Explicit {@code baseAmount} override when set; otherwise the configured {@link #formula} and knobs.
     */
    @Override
    public @Nullable Double baseAmount(DamageContext ctx) {
        Double explicit = super.baseAmount(ctx);
        return explicit != null ? explicit : computeAmount(ctx);
    }

    private double computeAmount(DamageContext ctx) {
        Float distance = fallDistance(ctx);
        if (distance == null || distance <= 0f) return 0.0;

        double safe = threshold(ctx) != null ? threshold(ctx) : 3.0;
        Formula mode = formula(ctx);
        if (mode == null) mode = Formula.LEGACY_CEIL;

        return switch (mode) {
            case LEGACY_CEIL -> Math.max(0.0, Math.ceil(distance - safe));
            case MODERN_FLOOR -> {
                double mod = effectiveDamageModifier(ctx);
                double mult = fallDamageMultiplier(ctx) != null ? fallDamageMultiplier(ctx) : 1.0;
                double power = distance + MODERN_EPSILON - safe;
                yield Math.max(0.0, Math.floor(power * mod * mult));
            }
        };
    }

    private static @Nullable Float fallDistance(DamageContext ctx) {
        FallDetail detail = ctx.detail(FallDetail.class);
        if (detail != null) return detail.distance();
        return ctx.detail(Float.class);
    }

    private double effectiveDamageModifier(DamageContext ctx) {
        FallDetail detail = ctx.detail(FallDetail.class);
        if (detail != null && detail.damageModifier() != null) return detail.damageModifier();
        Double cfg = damageModifier(ctx);
        return cfg != null ? cfg : 1.0;
    }

    @Override
    public DamageTypeConfig fromBase(DamageTypeConfig base) {
        DamageTypeConfig mergedCommon = super.fromBase(base);
        Builder b = new Builder();
        b.common.copyFrom(mergedCommon);
        if (base instanceof FallDamageConfig f) {
            b.formula = mergeFv(this.formula, f.formula);
            b.threshold = mergeFv(this.threshold, f.threshold);
            b.damageModifier = mergeFv(this.damageModifier, f.damageModifier);
            b.fallDamageMultiplier = mergeFv(this.fallDamageMultiplier, f.fallDamageMultiplier);
        } else {
            b.formula = this.formula;
            b.threshold = this.threshold;
            b.damageModifier = this.damageModifier;
            b.fallDamageMultiplier = this.fallDamageMultiplier;
        }
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final DamageTypeConfig.Builder common = new DamageTypeConfig.Builder().key(FallDamage.KEY);
        private FieldValue<DamageContext, Formula> formula = FieldValue.constant(Formula.LEGACY_CEIL);
        private FieldValue<DamageContext, Double> threshold = FieldValue.constant(3.0);
        private FieldValue<DamageContext, Double> damageModifier = FieldValue.constant(1.0);
        private FieldValue<DamageContext, Double> fallDamageMultiplier = FieldValue.constant(1.0);

        public Builder enabled(Boolean v) { common.enabled(v); return this; }
        public Builder enabled(Function<DamageContext, Boolean> fn) { common.enabled(fn); return this; }
        public Builder baseAmount(Double v) { common.baseAmount(v); return this; }
        public Builder baseAmount(Function<DamageContext, Double> fn) { common.baseAmount(fn); return this; }
        public Builder baseAmount(Double fallback, Function<DamageContext, Double> fn) { common.baseAmount(fallback, fn); return this; }
        public Builder invulTicks(Integer v) { common.invulTicks(v); return this; }
        public Builder invulTicks(Function<DamageContext, Integer> fn) { common.invulTicks(fn); return this; }
        public Builder invulTicks(Integer fallback, Function<DamageContext, Integer> fn) { common.invulTicks(fallback, fn); return this; }
        public Builder overdamage(Boolean v) { common.overdamage(v); return this; }
        public Builder overdamage(Function<DamageContext, Boolean> fn) { common.overdamage(fn); return this; }
        public Builder overdamage(Boolean fallback, Function<DamageContext, Boolean> fn) { common.overdamage(fallback, fn); return this; }
        public Builder silent(Boolean v) { common.silent(v); return this; }
        public Builder silent(Function<DamageContext, Boolean> fn) { common.silent(fn); return this; }
        public Builder silent(Boolean fallback, Function<DamageContext, Boolean> fn) { common.silent(fallback, fn); return this; }
        public Builder overdamageSilent(Boolean v) { common.overdamageSilent(v); return this; }
        public Builder overdamageSilent(Function<DamageContext, Boolean> fn) { common.overdamageSilent(fn); return this; }
        public Builder overdamageSilent(Boolean fallback, Function<DamageContext, Boolean> fn) { common.overdamageSilent(fallback, fn); return this; }
        public Builder triggersInvul(Boolean v) { common.triggersInvul(v); return this; }
        public Builder triggersInvul(Function<DamageContext, Boolean> fn) { common.triggersInvul(fn); return this; }
        public Builder bypassInvul(Boolean v) { common.bypassInvul(v); return this; }
        public Builder bypassInvul(Function<DamageContext, Boolean> fn) { common.bypassInvul(fn); return this; }
        public Builder subConfig(Function<DamageContext, DamageTypeConfig> fn) { common.subConfig(fn); return this; }

        public Builder formula(Formula v) { formula = FieldValue.constant(v); return this; }
        public Builder formula(Function<DamageContext, Formula> fn) { formula = FieldValue.of(fn); return this; }
        public Builder threshold(Double v) { threshold = FieldValue.constant(v); return this; }
        public Builder threshold(Function<DamageContext, Double> fn) { threshold = FieldValue.of(fn); return this; }
        public Builder damageModifier(Double v) { damageModifier = FieldValue.constant(v); return this; }
        public Builder damageModifier(Function<DamageContext, Double> fn) { damageModifier = FieldValue.of(fn); return this; }
        public Builder fallDamageMultiplier(Double v) { fallDamageMultiplier = FieldValue.constant(v); return this; }
        public Builder fallDamageMultiplier(Function<DamageContext, Double> fn) { fallDamageMultiplier = FieldValue.of(fn); return this; }

        public FallDamageConfig build() { return new FallDamageConfig(this); }
    }
}
