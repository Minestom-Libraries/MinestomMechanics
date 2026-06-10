package io.github.term4.minestommechanics.mechanics.attack;

import io.github.term4.minestommechanics.Vanilla18;
import io.github.term4.minestommechanics.api.event.AttackEvent;
import io.github.term4.minestommechanics.config.Config;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.attack.AttackConfigResolver.AttackContext;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Immutable attack config. Use {@link #builder()}, {@link #toBuilder()}. Deliberately minimal - the
 * {@link #ruleset} processor owns the attack behavior (damage/KB/sprint gating, hit queues, future
 * swing-hit detection), so the config only selects it, gates the pipeline, and picks the crit rule.
 */
public final class AttackConfig extends Config<AttackContext, AttackConfig> {

    public final FieldValue<AttackContext, Boolean> enabled;
    public final FieldValue<AttackContext, AttackEvent.AttackRule.Ruleset> ruleset;
    public final AttackEvent.CriticalRule criticalRule;

    private AttackConfig(Builder b) {
        super(b.subConfig);
        enabled = b.enabled;
        ruleset = b.ruleset;
        criticalRule = b.criticalRule;
    }

    /** Merges this config over base. */
    public AttackConfig fromBase(AttackConfig base) {
        return new Builder()
                .subConfig(subConfig != null ? subConfig : base.subConfig)
                .enabled(merge(enabled, base.enabled))
                .ruleset(merge(ruleset, base.ruleset))
                .criticalRule(criticalRule != null ? criticalRule : base.criticalRule)
                .build();
    }

    public Builder toBuilder() { return new Builder(this); }

    public static Builder builder() { return builder(null); }
    public static Builder builder(@Nullable AttackConfig base) { return base != null ? new Builder(base) : new Builder(); }

    /** Returns a new config with default values. */
    public static AttackConfig defaultConfig() { return builder().build(); }

    public static final class Builder {
        private Function<AttackContext, AttackConfig> subConfig;
        private FieldValue<AttackContext, Boolean> enabled;
        private FieldValue<AttackContext, AttackEvent.AttackRule.Ruleset> ruleset;
        private AttackEvent.CriticalRule criticalRule;

        Builder() {
            enabled = FieldValue.constant(true);
            ruleset = FieldValue.constant(Vanilla18.legacyAttack());
            criticalRule = null;
        }

        Builder(AttackConfig c) {
            subConfig = c.subConfig;
            enabled = c.enabled;
            ruleset = c.ruleset;
            criticalRule = c.criticalRule;
        }

        public Builder subConfig(Function<AttackContext, AttackConfig> fn) { subConfig = fn; return this; }

        public Builder enabled(Boolean v) { enabled = FieldValue.constant(v); return this; }
        public Builder enabled(Function<AttackContext, Boolean> fn) { enabled = FieldValue.of(fn); return this; }
        public Builder enabled(Boolean fallback, Function<AttackContext, Boolean> fn) { enabled = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder ruleset(AttackEvent.AttackRule.Ruleset v) { ruleset = FieldValue.constant(v); return this; }
        public Builder ruleset(Function<AttackContext, AttackEvent.AttackRule.Ruleset> fn) { ruleset = FieldValue.of(fn); return this; }
        public Builder ruleset(AttackEvent.AttackRule.Ruleset fallback, Function<AttackContext, AttackEvent.AttackRule.Ruleset> fn) { ruleset = FieldValue.ofWithFallback(fallback, fn); return this; }

        public Builder criticalRule(AttackEvent.CriticalRule v) { criticalRule = v; return this; }

        Builder enabled(FieldValue<AttackContext, Boolean> v) { enabled = v; return this; }
        Builder ruleset(FieldValue<AttackContext, AttackEvent.AttackRule.Ruleset> v) { ruleset = v; return this; }

        public AttackConfig build() { return new AttackConfig(this); }
    }
}
