package io.github.term4.minestommechanics.mechanics.damage.types.burning;

import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Config for the burning family ({@link InFireDamage}, {@link LavaDamage}, {@link BurningDamage}),
 * keyed per member. Adds the family's scheduling knobs on top of the common
 * {@link DamageTypeConfig} tunables:
 * <ul>
 *   <li>{@link #igniteTicks(DamageContext)} - fire ticks pinned on contact (in-fire / lava only;
 *       vanilla pins to {@code max(current, ignite)}); {@code null} or {@code <= 0} = no ignition,</li>
 *   <li>{@link #igniteWarmupTicks(DamageContext)} - explicit consecutive contact ticks before the first
 *       ignite (overrides the multiplier when set),</li>
 *   <li>{@link #igniteWarmupInvulMult(DamageContext)} - when warmup ticks are unset, ignite on the
 *       {@code N}th contact-damage hit ({@code N = mult}): contact threshold
 *       {@code (mult - 1) * invulTicks + 1} (1.8 ≈ {@code 2}, modern ≈ {@code 3}),</li>
 *   <li>{@link #contactIntervalTicks(DamageContext)} - ticks between contact-damage attempts while
 *       standing in fire/lava (vanilla = 1: attempt every tick; i-frames gate felt cadence),</li>
 *   <li>{@link #intervalTicks(DamageContext)} - ticks between burn hits while on fire
 *       ({@code on_fire} only; vanilla = 20),</li>
 *   <li>{@link #skipBurnWhileInLava(DamageContext)} - when true, {@code on_fire} ticks are skipped in lava
 *       (26.1 {@code Entity.tick}).</li>
 * </ul>
 * Vanilla values are wired in {@link io.github.term4.minestommechanics.mechanics.Vanilla18} /
 * {@link io.github.term4.minestommechanics.mechanics.Vanilla}.
 */
public final class BurningConfig extends DamageTypeConfig {

    private final @Nullable FieldValue<DamageContext, Integer> igniteTicks;
    private final @Nullable FieldValue<DamageContext, Integer> igniteWarmupTicks;
    private final @Nullable FieldValue<DamageContext, Integer> igniteWarmupInvulMult;
    private final @Nullable FieldValue<DamageContext, Integer> contactIntervalTicks;
    private final @Nullable FieldValue<DamageContext, Integer> intervalTicks;
    private final @Nullable FieldValue<DamageContext, Boolean> skipBurnWhileInLava;

    private BurningConfig(Builder b) {
        super(b.common);
        this.igniteTicks = b.igniteTicks;
        this.igniteWarmupTicks = b.igniteWarmupTicks;
        this.igniteWarmupInvulMult = b.igniteWarmupInvulMult;
        this.contactIntervalTicks = b.contactIntervalTicks;
        this.intervalTicks = b.intervalTicks;
        this.skipBurnWhileInLava = b.skipBurnWhileInLava;
    }

    /** Fire ticks pinned on contact (vanilla: in-fire 160, lava 300), or {@code null}/non-positive for none. */
    public @Nullable Integer igniteTicks(DamageContext ctx) { return resolve(igniteTicks, ctx); }

    /** Explicit contact ticks before first ignite; overrides {@link #igniteWarmupInvulMult} when set. */
    public @Nullable Integer igniteWarmupTicks(DamageContext ctx) { return resolve(igniteWarmupTicks, ctx); }

    /** Felt damage hits before first ignite when {@link #igniteWarmupTicks} is unset (1.8 = 2, modern = 3). */
    public @Nullable Integer igniteWarmupInvulMult(DamageContext ctx) { return resolve(igniteWarmupInvulMult, ctx); }

    /**
     * Contact tick threshold for the first ignite: explicit {@link #igniteWarmupTicks}, else
     * {@code (mult - 1) * invulTicks + 1} (aligns ignite with the {@code mult}th damage hit), else {@code 11}.
     */
    public int resolveIgniteWarmup(DamageContext ctx, @Nullable Integer invulTicks) {
        Integer explicit = igniteWarmupTicks(ctx);
        if (explicit != null && explicit > 0) return explicit;
        Integer mult = igniteWarmupInvulMult(ctx);
        if (mult != null && mult > 1 && invulTicks != null && invulTicks > 0) {
            return (mult - 1) * invulTicks + 1;
        }
        return 11;
    }

    /** Ticks between contact-damage attempts while in fire/lava (vanilla = 1). */
    public @Nullable Integer contactIntervalTicks(DamageContext ctx) { return resolve(contactIntervalTicks, ctx); }

    /** Ticks between successive burn hits while on fire (vanilla = 20). */
    public @Nullable Integer intervalTicks(DamageContext ctx) { return resolve(intervalTicks, ctx); }

    /** When true, {@code on_fire} damage is not applied while standing in lava (26.1). */
    public @Nullable Boolean skipBurnWhileInLava(DamageContext ctx) { return resolve(skipBurnWhileInLava, ctx); }

    @Override
    public DamageTypeConfig fromBase(DamageTypeConfig base) {
        DamageTypeConfig mergedCommon = super.fromBase(base);
        Builder b = new Builder();
        b.common.copyFrom(mergedCommon);
        if (base instanceof BurningConfig f) {
            b.igniteTicks = mergeFv(this.igniteTicks, f.igniteTicks);
            b.igniteWarmupTicks = mergeFv(this.igniteWarmupTicks, f.igniteWarmupTicks);
            b.igniteWarmupInvulMult = mergeFv(this.igniteWarmupInvulMult, f.igniteWarmupInvulMult);
            b.contactIntervalTicks = mergeFv(this.contactIntervalTicks, f.contactIntervalTicks);
            b.intervalTicks = mergeFv(this.intervalTicks, f.intervalTicks);
            b.skipBurnWhileInLava = mergeFv(this.skipBurnWhileInLava, f.skipBurnWhileInLava);
        } else {
            b.igniteTicks = this.igniteTicks;
            b.igniteWarmupTicks = this.igniteWarmupTicks;
            b.igniteWarmupInvulMult = this.igniteWarmupInvulMult;
            b.contactIntervalTicks = this.contactIntervalTicks;
            b.intervalTicks = this.intervalTicks;
            b.skipBurnWhileInLava = this.skipBurnWhileInLava;
        }
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final DamageTypeConfig.Builder common = new DamageTypeConfig.Builder();
        private FieldValue<DamageContext, Integer> igniteTicks;
        private FieldValue<DamageContext, Integer> igniteWarmupTicks;
        private FieldValue<DamageContext, Integer> igniteWarmupInvulMult;
        private FieldValue<DamageContext, Integer> contactIntervalTicks;
        private FieldValue<DamageContext, Integer> intervalTicks;
        private FieldValue<DamageContext, Boolean> skipBurnWhileInLava;

        public Builder key(Key key) { common.key(key); return this; }

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

        public Builder igniteTicks(Integer v) { igniteTicks = FieldValue.constant(v); return this; }
        public Builder igniteTicks(Function<DamageContext, Integer> fn) { igniteTicks = FieldValue.of(fn); return this; }
        public Builder igniteWarmupTicks(Integer v) { igniteWarmupTicks = FieldValue.constant(v); return this; }
        public Builder igniteWarmupTicks(Function<DamageContext, Integer> fn) { igniteWarmupTicks = FieldValue.of(fn); return this; }
        public Builder igniteWarmupInvulMult(Integer v) { igniteWarmupInvulMult = FieldValue.constant(v); return this; }
        public Builder igniteWarmupInvulMult(Function<DamageContext, Integer> fn) { igniteWarmupInvulMult = FieldValue.of(fn); return this; }
        public Builder contactIntervalTicks(Integer v) { contactIntervalTicks = FieldValue.constant(v); return this; }
        public Builder contactIntervalTicks(Function<DamageContext, Integer> fn) { contactIntervalTicks = FieldValue.of(fn); return this; }
        public Builder intervalTicks(Integer v) { intervalTicks = FieldValue.constant(v); return this; }
        public Builder intervalTicks(Function<DamageContext, Integer> fn) { intervalTicks = FieldValue.of(fn); return this; }

        public Builder skipBurnWhileInLava(Boolean v) { skipBurnWhileInLava = FieldValue.constant(v); return this; }
        public Builder skipBurnWhileInLava(Function<DamageContext, Boolean> fn) { skipBurnWhileInLava = FieldValue.of(fn); return this; }

        public BurningConfig build() { return new BurningConfig(this); }
    }
}
