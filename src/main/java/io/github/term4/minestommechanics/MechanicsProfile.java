package io.github.term4.minestommechanics;

import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.platform.player.PlayerConfig;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import org.jetbrains.annotations.Nullable;

/**
 * A bundle of mechanics configs assignable to a scope (player / instance / global) via
 * {@link MechanicsProfiles}. Members are nullable: a partial profile (e.g. knockback only) overrides just
 * that member and lets the rest fall through to the next scope. Presets compose directly:
 * {@code MechanicsProfile.of(Vanilla18.atk(), Vanilla18.dmg(), Vanilla18.kb())}.
 *
 * <p>{@code velocity} is the scope's velocity tracking method (vanilla {@code this.motX/motY/motZ}
 * reconstruction): the melee friction fold and the hurt broadcast read it for the victim, unless a
 * {@code KnockbackConfig.velocity} override is set. {@code projectiles} is the scope's projectile config.
 */
public record MechanicsProfile(
        @Nullable AttackConfig attack,
        @Nullable DamageConfig damage,
        @Nullable KnockbackConfig knockback,
        @Nullable PlayerConfig player,
        @Nullable VelocityRule velocity,
        @Nullable ProjectileConfig projectiles
) {

    /** Full profile with all three combat systems set (no player platform / velocity / projectiles). */
    public static MechanicsProfile of(AttackConfig attack, DamageConfig damage, KnockbackConfig knockback) {
        return new MechanicsProfile(attack, damage, knockback, null, null, null);
    }

    public Builder toBuilder() { return new Builder(this); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private @Nullable AttackConfig attack;
        private @Nullable DamageConfig damage;
        private @Nullable KnockbackConfig knockback;
        private @Nullable PlayerConfig player;
        private @Nullable VelocityRule velocity;
        private @Nullable ProjectileConfig projectiles;

        Builder() {}

        Builder(MechanicsProfile p) {
            attack = p.attack;
            damage = p.damage;
            knockback = p.knockback;
            player = p.player;
            velocity = p.velocity;
            projectiles = p.projectiles;
        }

        public Builder attack(@Nullable AttackConfig v) { attack = v; return this; }
        public Builder damage(@Nullable DamageConfig v) { damage = v; return this; }
        public Builder knockback(@Nullable KnockbackConfig v) { knockback = v; return this; }
        public Builder player(@Nullable PlayerConfig v) { player = v; return this; }
        public Builder velocity(@Nullable VelocityRule v) { velocity = v; return this; }
        public Builder projectiles(@Nullable ProjectileConfig v) { projectiles = v; return this; }

        public MechanicsProfile build() {
            return new MechanicsProfile(attack, damage, knockback, player, velocity, projectiles);
        }
    }
}
