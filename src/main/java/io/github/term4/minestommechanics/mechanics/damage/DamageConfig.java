package io.github.term4.minestommechanics.mechanics.damage;

import io.github.term4.minestommechanics.config.Config;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Immutable damage config. Use {@link #builder()}, {@link #toBuilder()}. Mirrors KnockbackConfig. */
public final class DamageConfig extends Config<DamageContext, DamageConfig> {

    public final FieldValue<DamageContext, Integer> invulTicks;
    public final FieldValue<DamageContext, Boolean> enableOverdamage;
    public final FieldValue<DamageContext, Boolean> silent;
    public final FieldValue<DamageContext, Boolean> overdamageSilent;
    /**
     * When true, fresh hits (except drowning) broadcast the victim's current velocity like 1.8
     * {@code EntityTrackerEntry} after {@code EntityLiving.ac()} / 26.1 {@code hurtMarked}.
     */
    public final FieldValue<DamageContext, Boolean> syncHurtVelocity;
    /**
     * The knockback config the {@link #syncHurtVelocity hurt broadcast} routes through the KnockbackSystem
     * (vanilla {@code ac()}: a fresh non-melee, non-drown hit broadcasts the victim's server-tracked velocity).
     * A zero-impulse config whose velocity fold IS the broadcast - see
     * {@code Vanilla18.hurtKb()} (the default when unset). All velocity-send concerns (the fold rule,
     * {@code quantizeVelocity}, entity push) live on this config, keeping one send path and one set of knobs.
     */
    public final FieldValue<DamageContext, KnockbackConfig> hurtKnockback;

    /** Per-type config overrides, keyed by {@link DamageTypeConfig#key()}. Unset knobs fall back to this config. */
    public final Map<Key, DamageTypeConfig> typeConfigs;

    /**
     * Pluggable transforms applied in order after the {@link io.github.term4.minestommechanics.api.event.DamageEvent}
     * fires. Each {@link DamageComponent} self-gates and may adjust the amount about to be applied.
     * TODO(stages): same per-stage strategy plan as knockback (see KnockbackCalculator) - let users replace a
     *  built-in formula (e.g. the invul/overdamage replacement rule) instead of only appending post-transforms.
     */
    @Nullable public final List<DamageComponent> customComponents;

    private DamageConfig(Builder b) {
        super(b.subConfig);
        invulTicks = b.invulTicks;
        enableOverdamage = b.enableOverdamage;
        silent = b.silent;
        overdamageSilent = b.overdamageSilent;
        syncHurtVelocity = b.syncHurtVelocity;
        hurtKnockback = b.hurtKnockback;
        typeConfigs = Map.copyOf(b.typeConfigs);
        customComponents = b.customComponents;
    }

    /** Per-type config for {@code key}, or {@code null} if none was registered. */
    public @Nullable DamageTypeConfig typeConfig(Key key) {
        return typeConfigs.get(key);
    }

    /** Merges this config over base. */
    public DamageConfig fromBase(DamageConfig base) {
        Map<Key, DamageTypeConfig> mergedTypes = new LinkedHashMap<>(base.typeConfigs);
        mergedTypes.putAll(typeConfigs);
        return new Builder()
                .subConfig(subConfig != null ? subConfig : base.subConfig)
                .invulTicks(merge(invulTicks, base.invulTicks))
                .enableOverdamage(merge(enableOverdamage, base.enableOverdamage))
                .silent(merge(silent, base.silent))
                .overdamageSilent(merge(overdamageSilent, base.overdamageSilent))
                .syncHurtVelocity(merge(syncHurtVelocity, base.syncHurtVelocity))
                .hurtKnockback(merge(hurtKnockback, base.hurtKnockback))
                .typeConfigs(mergedTypes)
                .customComponents(customComponents != null ? customComponents : base.customComponents)
                .build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return builder(null);
    }

    public static Builder builder(@Nullable DamageConfig base) {
        return base != null ? new Builder(base) : new Builder();
    }

    public static final class Builder {
        private Function<DamageContext, DamageConfig> subConfig;
        private FieldValue<DamageContext, Integer> invulTicks;
        private FieldValue<DamageContext, Boolean> enableOverdamage;
        private FieldValue<DamageContext, Boolean> silent;
        private FieldValue<DamageContext, Boolean> overdamageSilent;
        private FieldValue<DamageContext, Boolean> syncHurtVelocity;
        private FieldValue<DamageContext, KnockbackConfig> hurtKnockback;
        private final Map<Key, DamageTypeConfig> typeConfigs = new LinkedHashMap<>();
        private List<DamageComponent> customComponents;

        Builder() {}

        Builder(DamageConfig c) {
            subConfig = c.subConfig;
            invulTicks = c.invulTicks;
            enableOverdamage = c.enableOverdamage;
            silent = c.silent;
            overdamageSilent = c.overdamageSilent;
            syncHurtVelocity = c.syncHurtVelocity;
            hurtKnockback = c.hurtKnockback;
            typeConfigs.putAll(c.typeConfigs);
            customComponents = c.customComponents;
        }

        public Builder subConfig(Function<DamageContext, DamageConfig> fn) { subConfig = fn; return this; }
        public Builder invulTicks(Integer v) { invulTicks = FieldValue.constant(v); return this; }
        public Builder invulTicks(Function<DamageContext, Integer> fn) { invulTicks = FieldValue.of(fn); return this; }
        public Builder invulTicks(Integer fallback, Function<DamageContext, Integer> fn) { invulTicks = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder enableOverdamage(Boolean v) { enableOverdamage = FieldValue.constant(v); return this; }
        public Builder enableOverdamage(Function<DamageContext, Boolean> fn) { enableOverdamage = FieldValue.of(fn); return this; }
        public Builder enableOverdamage(Boolean fallback, Function<DamageContext, Boolean> fn) { enableOverdamage = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder silent(Boolean v) { silent = FieldValue.constant(v); return this; }
        public Builder silent(Function<DamageContext, Boolean> fn) { silent = FieldValue.of(fn); return this; }
        public Builder silent(Boolean fallback, Function<DamageContext, Boolean> fn) { silent = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder overdamageSilent(Boolean v) { overdamageSilent = FieldValue.constant(v); return this; }
        public Builder overdamageSilent(Function<DamageContext, Boolean> fn) { overdamageSilent = FieldValue.of(fn); return this; }
        public Builder overdamageSilent(Boolean fallback, Function<DamageContext, Boolean> fn) { overdamageSilent = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder syncHurtVelocity(Boolean v) { syncHurtVelocity = FieldValue.constant(v); return this; }
        public Builder syncHurtVelocity(Function<DamageContext, Boolean> fn) { syncHurtVelocity = FieldValue.of(fn); return this; }
        public Builder syncHurtVelocity(Boolean fallback, Function<DamageContext, Boolean> fn) { syncHurtVelocity = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder hurtKnockback(KnockbackConfig v) { hurtKnockback = FieldValue.constant(v); return this; }
        public Builder hurtKnockback(Function<DamageContext, KnockbackConfig> fn) { hurtKnockback = FieldValue.of(fn); return this; }
        public Builder hurtKnockback(KnockbackConfig fallback, Function<DamageContext, KnockbackConfig> fn) { hurtKnockback = FieldValue.ofWithFallback(fallback, fn); return this; }

        /** Adds a single per-type config override, keyed by its {@link DamageTypeConfig#key()}. */
        public Builder typeConfig(DamageTypeConfig cfg) { typeConfigs.put(cfg.key(), cfg); return this; }

        /** Adds per-type config overrides, each keyed by its {@link DamageTypeConfig#key()}. */
        public Builder typeConfigs(DamageTypeConfig... cfgs) {
            for (DamageTypeConfig cfg : cfgs) typeConfigs.put(cfg.key(), cfg);
            return this;
        }

        public Builder addCustomComponent(DamageComponent component) {
            List<DamageComponent> list = customComponents == null ? new ArrayList<>() : new ArrayList<>(customComponents);
            list.add(component);
            customComponents = list;
            return this;
        }

        Builder invulTicks(FieldValue<DamageContext, Integer> v) { invulTicks = v; return this; }
        Builder enableOverdamage(FieldValue<DamageContext, Boolean> v) { enableOverdamage = v; return this; }
        Builder silent(FieldValue<DamageContext, Boolean> v) { silent = v; return this; }
        Builder overdamageSilent(FieldValue<DamageContext, Boolean> v) { overdamageSilent = v; return this; }
        Builder syncHurtVelocity(FieldValue<DamageContext, Boolean> v) { syncHurtVelocity = v; return this; }
        Builder hurtKnockback(FieldValue<DamageContext, KnockbackConfig> v) { hurtKnockback = v; return this; }
        Builder typeConfigs(Map<Key, DamageTypeConfig> cfgs) { typeConfigs.putAll(cfgs); return this; }
        Builder customComponents(List<DamageComponent> components) { customComponents = components; return this; }

        public DamageConfig build() {
            return new DamageConfig(this);
        }
    }
}
