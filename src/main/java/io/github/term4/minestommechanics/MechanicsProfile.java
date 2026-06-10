package io.github.term4.minestommechanics;

import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import org.jetbrains.annotations.Nullable;

/**
 * A bundle of mechanics configs assignable to a scope (player / instance / global) via
 * {@link MechanicsProfiles}. Members are nullable: a partial profile (e.g. knockback only) overrides just
 * that system and lets the rest fall through to the next scope. Presets compose directly:
 * {@code MechanicsProfile.of(Vanilla18.atk(), Vanilla18.dmg(), Vanilla18.kb())}.
 */
public record MechanicsProfile(
        @Nullable AttackConfig attack,
        @Nullable DamageConfig damage,
        @Nullable KnockbackConfig knockback
) {

    /** Full profile with all three systems set. */
    public static MechanicsProfile of(AttackConfig attack, DamageConfig damage, KnockbackConfig knockback) {
        return new MechanicsProfile(attack, damage, knockback);
    }

    public Builder toBuilder() { return new Builder(this); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private @Nullable AttackConfig attack;
        private @Nullable DamageConfig damage;
        private @Nullable KnockbackConfig knockback;

        Builder() {}

        Builder(MechanicsProfile p) {
            attack = p.attack;
            damage = p.damage;
            knockback = p.knockback;
        }

        public Builder attack(@Nullable AttackConfig v) { attack = v; return this; }
        public Builder damage(@Nullable DamageConfig v) { damage = v; return this; }
        public Builder knockback(@Nullable KnockbackConfig v) { knockback = v; return this; }

        public MechanicsProfile build() {
            return new MechanicsProfile(attack, damage, knockback);
        }
    }
}
