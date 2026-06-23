package io.github.term4.minestommechanics;

import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeConfig;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingConfig;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.durability.DurabilityConfig;
import io.github.term4.minestommechanics.mechanics.hunger.HungerConfig;
import io.github.term4.minestommechanics.platform.fixes.FixesConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.platform.compatibility.CompatConfig;
import io.github.term4.minestommechanics.platform.player.PlayerConfig;
import io.github.term4.minestommechanics.tracking.motion.VelocityRule;
import io.github.term4.minestommechanics.util.tick.TickScalingConfig;
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
        @Nullable ProjectileConfig projectiles,
        @Nullable FixesConfig fixes,
        @Nullable AttributeConfig attributes,
        @Nullable TickScalingConfig tickScaling,
        @Nullable DurabilityConfig durability,
        @Nullable HungerConfig hunger,
        @Nullable ConsumableConfig consumables,
        @Nullable BlockingConfig blocking,
        @Nullable CompatConfig compat
) {

    /** Full profile with all three combat systems set (the remaining members - platform / velocity / projectiles / fixes / attributes / scaling / durability / hunger / consumables / blocking / compat - default to null). */
    public static MechanicsProfile of(AttackConfig attack, DamageConfig damage, KnockbackConfig knockback) {
        return new MechanicsProfile(attack, damage, knockback, null, null, null, null, null, null, null, null, null, null, null);
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
        private @Nullable FixesConfig fixes;
        private @Nullable AttributeConfig attributes;
        private @Nullable TickScalingConfig tickScaling;
        private @Nullable DurabilityConfig durability;
        private @Nullable HungerConfig hunger;
        private @Nullable ConsumableConfig consumables;
        private @Nullable BlockingConfig blocking;
        private @Nullable CompatConfig compat;

        Builder() {}

        Builder(MechanicsProfile p) {
            attack = p.attack;
            damage = p.damage;
            knockback = p.knockback;
            player = p.player;
            velocity = p.velocity;
            projectiles = p.projectiles;
            fixes = p.fixes;
            attributes = p.attributes;
            tickScaling = p.tickScaling;
            durability = p.durability;
            hunger = p.hunger;
            consumables = p.consumables;
            blocking = p.blocking;
            compat = p.compat;
        }

        public Builder attack(@Nullable AttackConfig v) { attack = v; return this; }
        public Builder damage(@Nullable DamageConfig v) { damage = v; return this; }
        public Builder knockback(@Nullable KnockbackConfig v) { knockback = v; return this; }
        public Builder player(@Nullable PlayerConfig v) { player = v; return this; }
        public Builder velocity(@Nullable VelocityRule v) { velocity = v; return this; }
        public Builder projectiles(@Nullable ProjectileConfig v) { projectiles = v; return this; }
        public Builder fixes(@Nullable FixesConfig v) { fixes = v; return this; }
        public Builder attributes(@Nullable AttributeConfig v) { attributes = v; return this; }
        public Builder tickScaling(@Nullable TickScalingConfig v) { tickScaling = v; return this; }
        public Builder durability(@Nullable DurabilityConfig v) { durability = v; return this; }
        public Builder hunger(@Nullable HungerConfig v) { hunger = v; return this; }
        public Builder consumables(@Nullable ConsumableConfig v) { consumables = v; return this; }
        public Builder blocking(@Nullable BlockingConfig v) { blocking = v; return this; }
        public Builder compat(@Nullable CompatConfig v) { compat = v; return this; }

        public MechanicsProfile build() {
            return new MechanicsProfile(attack, damage, knockback, player, velocity, projectiles, fixes, attributes, tickScaling, durability, hunger, consumables, blocking, compat);
        }
    }
}
