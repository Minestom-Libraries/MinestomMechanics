package io.github.term4.minestommechanics.mechanics.explosion;

import io.github.term4.minestommechanics.config.Config;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionConfigResolver.ExplosionContext;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Immutable per-explosion config (radius, damage curve, knockback, exposure). Mitigation/i-frames/death are the
 * {@code DamageSystem}'s, not configured here. Use {@link #builder()}, {@link #toBuilder()}.
 */
public final class ExplosionConfig extends Config<ExplosionContext, ExplosionConfig> {

    /** Default radius for the no-power {@code explode} overloads; an explicit call power wins. */
    public final FieldValue<ExplosionContext, Double> power;
    /** Damage-curve constant: 8.0 (1.8) or 7.0 (modern). */
    public final FieldValue<ExplosionContext, Double> damageConstant;
    /** Floor the per-entity damage to an int (1.8 parity). */
    public final FieldValue<ExplosionContext, Boolean> floorDamage;
    public final FieldValue<ExplosionContext, Double> knockbackMultiplier;
    /** Raytrace each entity's line-of-sight exposure (default on); off = full exposure for everyone in range. */
    public final FieldValue<ExplosionContext, Boolean> exposure;
    /** Carried on {@code ExplosionEvent} for a block/fire listener; the library itself never sets fire. */
    public final FieldValue<ExplosionContext, Boolean> fire;
    /** Whether the source entity is hit by its own explosion (vanilla excludes it). */
    public final FieldValue<ExplosionContext, Boolean> affectsSource;

    private ExplosionConfig(Builder b) {
        super(b.subConfig);
        power = b.power;
        damageConstant = b.damageConstant;
        floorDamage = b.floorDamage;
        knockbackMultiplier = b.knockbackMultiplier;
        exposure = b.exposure;
        fire = b.fire;
        affectsSource = b.affectsSource;
    }

    /** Merges this config over base. */
    public ExplosionConfig fromBase(ExplosionConfig base) {
        return new Builder()
                .subConfig(subConfig != null ? subConfig : base.subConfig)
                .power(merge(power, base.power))
                .damageConstant(merge(damageConstant, base.damageConstant))
                .floorDamage(merge(floorDamage, base.floorDamage))
                .knockbackMultiplier(merge(knockbackMultiplier, base.knockbackMultiplier))
                .exposure(merge(exposure, base.exposure))
                .fire(merge(fire, base.fire))
                .affectsSource(merge(affectsSource, base.affectsSource))
                .build();
    }

    public Builder toBuilder() { return new Builder(this); }

    public static Builder builder() { return new Builder(); }

    public static Builder builder(@Nullable ExplosionConfig base) {
        return base != null ? new Builder(base) : new Builder();
    }

    public static final class Builder {
        private Function<ExplosionContext, ExplosionConfig> subConfig;
        private FieldValue<ExplosionContext, Double> power;
        private FieldValue<ExplosionContext, Double> damageConstant;
        private FieldValue<ExplosionContext, Boolean> floorDamage;
        private FieldValue<ExplosionContext, Double> knockbackMultiplier;
        private FieldValue<ExplosionContext, Boolean> exposure;
        private FieldValue<ExplosionContext, Boolean> fire;
        private FieldValue<ExplosionContext, Boolean> affectsSource;

        Builder() {}

        Builder(ExplosionConfig c) {
            subConfig = c.subConfig;
            power = c.power;
            damageConstant = c.damageConstant;
            floorDamage = c.floorDamage;
            knockbackMultiplier = c.knockbackMultiplier;
            exposure = c.exposure;
            fire = c.fire;
            affectsSource = c.affectsSource;
        }

        public Builder subConfig(Function<ExplosionContext, ExplosionConfig> fn) { subConfig = fn; return this; }
        public Builder power(Double v) { power = FieldValue.constant(v); return this; }
        public Builder power(Function<ExplosionContext, Double> fn) { power = FieldValue.of(fn); return this; }
        public Builder damageConstant(Double v) { damageConstant = FieldValue.constant(v); return this; }
        public Builder damageConstant(Function<ExplosionContext, Double> fn) { damageConstant = FieldValue.of(fn); return this; }
        public Builder floorDamage(Boolean v) { floorDamage = FieldValue.constant(v); return this; }
        public Builder floorDamage(Function<ExplosionContext, Boolean> fn) { floorDamage = FieldValue.of(fn); return this; }
        public Builder knockbackMultiplier(Double v) { knockbackMultiplier = FieldValue.constant(v); return this; }
        public Builder knockbackMultiplier(Function<ExplosionContext, Double> fn) { knockbackMultiplier = FieldValue.of(fn); return this; }
        public Builder exposure(Boolean v) { exposure = FieldValue.constant(v); return this; }
        public Builder exposure(Function<ExplosionContext, Boolean> fn) { exposure = FieldValue.of(fn); return this; }
        public Builder fire(Boolean v) { fire = FieldValue.constant(v); return this; }
        public Builder fire(Function<ExplosionContext, Boolean> fn) { fire = FieldValue.of(fn); return this; }
        public Builder affectsSource(Boolean v) { affectsSource = FieldValue.constant(v); return this; }
        public Builder affectsSource(Function<ExplosionContext, Boolean> fn) { affectsSource = FieldValue.of(fn); return this; }

        Builder power(FieldValue<ExplosionContext, Double> v) { power = v; return this; }
        Builder damageConstant(FieldValue<ExplosionContext, Double> v) { damageConstant = v; return this; }
        Builder floorDamage(FieldValue<ExplosionContext, Boolean> v) { floorDamage = v; return this; }
        Builder knockbackMultiplier(FieldValue<ExplosionContext, Double> v) { knockbackMultiplier = v; return this; }
        Builder exposure(FieldValue<ExplosionContext, Boolean> v) { exposure = v; return this; }
        Builder fire(FieldValue<ExplosionContext, Boolean> v) { fire = v; return this; }
        Builder affectsSource(FieldValue<ExplosionContext, Boolean> v) { affectsSource = v; return this; }

        public ExplosionConfig build() { return new ExplosionConfig(this); }
    }
}
