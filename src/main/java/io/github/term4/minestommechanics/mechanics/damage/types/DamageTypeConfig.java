package io.github.term4.minestommechanics.mechanics.damage.types;

import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Per-type damage configuration: the common tunables shared by every {@link DamageType}, keyed by the
 * type's {@link #key()}. Attached to the global {@link DamageConfig} via
 * {@link DamageConfig.Builder#typeConfigs}; a type without an entry falls back to its
 * {@link DamageType#defaultConfig()}.
 *
 * <p>Every value is a {@link FieldValue} resolved against a {@link DamageContext}, so any field can be
 * a constant or a context-aware lambda. {@code invulTicks}/{@code overdamage} resolve to {@code null}
 * when unset, inheriting the global {@link DamageConfig}. Subclasses add type-specific knobs and live
 * in the same sub-package as their type; they compose (not inherit) this builder for the common knobs.
 */
public class DamageTypeConfig {

    private final Key key;
    private final @Nullable FieldValue<DamageContext, Boolean> enabled;
    private final @Nullable FieldValue<DamageContext, Double> baseAmount;
    private final @Nullable FieldValue<DamageContext, Integer> invulTicks;
    private final @Nullable FieldValue<DamageContext, Boolean> overdamage;
    private final @Nullable FieldValue<DamageContext, Boolean> silent;
    private final @Nullable FieldValue<DamageContext, Boolean> overdamageSilent;
    private final @Nullable FieldValue<DamageContext, Boolean> triggersInvul;
    private final @Nullable FieldValue<DamageContext, Boolean> bypassInvul;
    private final @Nullable Function<DamageContext, DamageTypeConfig> subConfig;

    protected DamageTypeConfig(Builder b) {
        this.key = b.key;
        this.enabled = b.enabled;
        this.baseAmount = b.baseAmount;
        this.invulTicks = b.invulTicks;
        this.overdamage = b.overdamage;
        this.silent = b.silent;
        this.overdamageSilent = b.overdamageSilent;
        this.triggersInvul = b.triggersInvul;
        this.bypassInvul = b.bypassInvul;
        this.subConfig = b.subConfig;
    }

    /** Identity of the type this config applies to. */
    public Key key() { return key; }

    /**
     * Whether this damage type applies at all in the resolved scope (defaults to {@code true}).
     * Resolved per victim through the config chain, so a profile can switch a type off per
     * player/instance/global without touching its producer.
     */
    public boolean enabled(DamageContext ctx) {
        Boolean v = resolve(enabled, ctx);
        return v == null || v;
    }

    /** Default damage amount for this context, or {@code null} (treated as 0) when a snapshot doesn't override it. */
    public @Nullable Double baseAmount(DamageContext ctx) { return resolve(baseAmount, ctx); }

    /** Per-type invulnerability window in ticks, or {@code null} to inherit {@link DamageConfig#invulTicks}. */
    public @Nullable Integer invulTicks(DamageContext ctx) { return resolve(invulTicks, ctx); }

    /** Constant per-type invul window when configured as a constant, else {@code null} (unset or context-dependent). */
    public @Nullable Integer invulTicksConstant() { return invulTicks != null ? invulTicks.constantOrNull() : null; }

    /** Per-type overdamage (damage-replacement), or {@code null} to inherit {@link DamageConfig#enableOverdamage}. */
    public @Nullable Boolean overdamage(DamageContext ctx) { return resolve(overdamage, ctx); }

    /** Per-type silent-damage (no hurt animation) for all hits, or {@code null} to inherit {@link DamageConfig#silent}. */
    public @Nullable Boolean silent(DamageContext ctx) { return resolve(silent, ctx); }

    /** Per-type silent-damage applied only to overdamage replacement hits, or {@code null} to inherit {@link DamageConfig#overdamageSilent}. */
    public @Nullable Boolean overdamageSilent(DamageContext ctx) { return resolve(overdamageSilent, ctx); }

    /** Whether applying this type starts the damage invulnerability window (defaults to {@code true}). */
    public boolean triggersInvul(DamageContext ctx) {
        Boolean v = resolve(triggersInvul, ctx);
        return v == null || v;
    }

    /** Whether this type ignores the target's damage invulnerability window (defaults to {@code false}). */
    public boolean bypassInvul(DamageContext ctx) {
        Boolean v = resolve(bypassInvul, ctx);
        return v != null && v;
    }

    /** Context-aware overlay applied over this type config before resolution, or {@code null} if none. */
    public @Nullable Function<DamageContext, DamageTypeConfig> subConfig() { return subConfig; }

    /**
     * Merges this type config over {@code base}: this config's set fields win, unset fields fall back
     * to {@code base} per resolution. Subclasses override to also merge their type-specific fields.
     */
    public DamageTypeConfig fromBase(DamageTypeConfig base) {
        Builder b = new Builder();
        b.key = key != null ? key : base.key;
        b.enabled = mergeFv(enabled, base.enabled);
        b.baseAmount = mergeFv(baseAmount, base.baseAmount);
        b.invulTicks = mergeFv(invulTicks, base.invulTicks);
        b.overdamage = mergeFv(overdamage, base.overdamage);
        b.silent = mergeFv(silent, base.silent);
        b.overdamageSilent = mergeFv(overdamageSilent, base.overdamageSilent);
        b.triggersInvul = mergeFv(triggersInvul, base.triggersInvul);
        b.bypassInvul = mergeFv(bypassInvul, base.bypassInvul);
        b.subConfig = subConfig != null ? subConfig : base.subConfig;
        return b.build();
    }

    protected static <T> @Nullable T resolve(@Nullable FieldValue<DamageContext, T> fv, DamageContext ctx) {
        return fv != null ? fv.resolve(ctx) : null;
    }

    /** Layers {@code a} over {@code b}: {@code a} wins, falling back to {@code b} per resolution. */
    protected static <T> @Nullable FieldValue<DamageContext, T> mergeFv(@Nullable FieldValue<DamageContext, T> a,
                                                                        @Nullable FieldValue<DamageContext, T> b) {
        if (b == null) return a;
        if (a == null) return b;
        return a.or(b);
    }

    /** A builder for the base config; a key is required since configs are keyed by type. */
    public static Builder builder(Key key) { return new Builder().key(key); }

    /** Plain builder for the common knobs. Subclass builders compose one of these and delegate to it. */
    public static class Builder {
        private Key key;
        private FieldValue<DamageContext, Boolean> enabled;
        private FieldValue<DamageContext, Double> baseAmount;
        private FieldValue<DamageContext, Integer> invulTicks;
        private FieldValue<DamageContext, Boolean> overdamage;
        private FieldValue<DamageContext, Boolean> silent;
        private FieldValue<DamageContext, Boolean> overdamageSilent;
        private FieldValue<DamageContext, Boolean> triggersInvul;
        private FieldValue<DamageContext, Boolean> bypassInvul;
        private Function<DamageContext, DamageTypeConfig> subConfig;

        public Builder key(Key key) { this.key = key; return this; }

        /** Copies every common field (and subConfig/key) from {@code src} into this builder. */
        public Builder copyFrom(DamageTypeConfig src) {
            this.key = src.key;
            this.enabled = src.enabled;
            this.baseAmount = src.baseAmount;
            this.invulTicks = src.invulTicks;
            this.overdamage = src.overdamage;
            this.silent = src.silent;
            this.overdamageSilent = src.overdamageSilent;
            this.triggersInvul = src.triggersInvul;
            this.bypassInvul = src.bypassInvul;
            this.subConfig = src.subConfig;
            return this;
        }

        public Builder enabled(Boolean v) { enabled = FieldValue.constant(v); return this; }
        public Builder enabled(Function<DamageContext, Boolean> fn) { enabled = FieldValue.of(fn); return this; }
        public Builder enabled(Boolean fallback, Function<DamageContext, Boolean> fn) { enabled = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder baseAmount(Double v) { baseAmount = FieldValue.constant(v); return this; }
        public Builder baseAmount(Function<DamageContext, Double> fn) { baseAmount = FieldValue.of(fn); return this; }
        public Builder baseAmount(Double fallback, Function<DamageContext, Double> fn) { baseAmount = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder invulTicks(Integer v) { invulTicks = FieldValue.constant(v); return this; }
        public Builder invulTicks(Function<DamageContext, Integer> fn) { invulTicks = FieldValue.of(fn); return this; }
        public Builder invulTicks(Integer fallback, Function<DamageContext, Integer> fn) { invulTicks = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder overdamage(Boolean v) { overdamage = FieldValue.constant(v); return this; }
        public Builder overdamage(Function<DamageContext, Boolean> fn) { overdamage = FieldValue.of(fn); return this; }
        public Builder overdamage(Boolean fallback, Function<DamageContext, Boolean> fn) { overdamage = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder silent(Boolean v) { silent = FieldValue.constant(v); return this; }
        public Builder silent(Function<DamageContext, Boolean> fn) { silent = FieldValue.of(fn); return this; }
        public Builder silent(Boolean fallback, Function<DamageContext, Boolean> fn) { silent = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder overdamageSilent(Boolean v) { overdamageSilent = FieldValue.constant(v); return this; }
        public Builder overdamageSilent(Function<DamageContext, Boolean> fn) { overdamageSilent = FieldValue.of(fn); return this; }
        public Builder overdamageSilent(Boolean fallback, Function<DamageContext, Boolean> fn) { overdamageSilent = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder triggersInvul(Boolean v) { triggersInvul = FieldValue.constant(v); return this; }
        public Builder triggersInvul(Function<DamageContext, Boolean> fn) { triggersInvul = FieldValue.of(fn); return this; }

        public Builder bypassInvul(Boolean v) { bypassInvul = FieldValue.constant(v); return this; }
        public Builder bypassInvul(Function<DamageContext, Boolean> fn) { bypassInvul = FieldValue.of(fn); return this; }

        public Builder subConfig(Function<DamageContext, DamageTypeConfig> fn) { subConfig = fn; return this; }

        public DamageTypeConfig build() { return new DamageTypeConfig(this); }
    }
}
