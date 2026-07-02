package io.github.term4.minestommechanics.mechanics.vanilla18;

import io.github.term4.minestommechanics.mechanics.damage.types.projectile.ProjectileDamage;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.mechanics.projectile.shootables.Bow;
import io.github.term4.minestommechanics.mechanics.projectile.types.Arrow;
import io.github.term4.minestommechanics.mechanics.projectile.types.Egg;
import io.github.term4.minestommechanics.mechanics.projectile.types.Pearl;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import io.github.term4.minestommechanics.mechanics.projectile.types.Snowball;
import io.github.term4.minestommechanics.mechanics.projectile.types.SplashPotion;

/**
 * Vanilla 1.8 projectile config: the generic {@link #defaults()} baseline plus per-type entries. The canonical 1.8
 * values, consumed both by the {@link Vanilla18} preset profile and by {@code ProjectileTypeConfig}/the flight entities.
 */
public final class Projectiles {

    private Projectiles() {}

    /**
     * Vanilla 1.8 projectile config: the generic {@link #defaults()} baseline plus per-type entries (presence enables a
     * type at install). All three throwables share the baseline; their differences are in the flight entity (egg -&gt;
     * baby chicken, pearl -&gt; teleport).
     */
    public static ProjectileConfig config() {
        return ProjectileConfig.builder()
                .defaults(defaults())
                .typeConfigs(
                        ProjectileTypeConfig.builder(Snowball.KEY).build(),
                        ProjectileTypeConfig.builder(Egg.KEY).build(),
                        pearl(),
                        arrow(),
                        splashPotion())
                .shootables(new Bow()) // the bow launcher (item -> arrow); exists in 1.8
                .build();
    }

    /** The generic vanilla 1.8 throwable baseline every type inherits unless it overrides a knob (values + rationale inline). Re-base per-type via {@code ProjectileTypeConfig.builder(Projectiles.defaults())...}. */
    public static ProjectileTypeConfig defaults() {
        return ProjectileTypeConfig.builder()
                .boundingBox(0, 0, 0)
                .gravity(0.03).horizontalDrag(0.99).verticalDrag(0.99)
                .speed(1.5).spread(0.0075) // momentumHorizontal/Vertical default 0 (1.8 folds no shooter momentum)
                .spawnOffsetVertical(-0.1).spawnOffsetSideways(0.16)
                .shooterImmunityTicks(5)
                .entityHitGrow(0.3)
                .syncInterval(20)
                .knockback(Knockback.projectile())
                .knockbackSource(ProjectileTypeConfig.KnockbackSource.SHOOTER)
                .damage(0.0).damageType(ProjectileDamage.INSTANCE)
                .removeOnEntityHit(true).removeOnBlockHit(true)
                .invulnHit(ProjectileTypeConfig.HitResponse.DESTROY)
                .build();
    }

    /**
     * Vanilla 1.8 ender pearl overrides (on {@link #defaults()}): {@code selfHit(PASS_THROUGH)} - the 1.8 pearl ignores
     * its own thrower and passes through (unlike snowball/egg, which can self-hit after the immunity window). The teleport
     * + 5 fall damage live in {@code PearlEntity}.
     */
    public static ProjectileTypeConfig pearl() {
        return ProjectileTypeConfig.builder(Pearl.KEY)
                .selfHit(ProjectileTypeConfig.HitResponse.PASS_THROUGH)
                .build();
    }

    /**
     * Vanilla 1.8 splash potion overrides (on {@link #defaults()}): lobbed slow + high (speed {@code 0.5}, pitch offset
     * {@code -20}, gravity {@code 0.05}) and never a contact hit ({@code entityHit/selfHit DESTROY} - the impact splash in
     * {@code SplashPotionEntity} is the whole effect, no hurt animation / knockback / invul).
     */
    public static ProjectileTypeConfig splashPotion() {
        return ProjectileTypeConfig.builder(SplashPotion.KEY)
                .gravity(0.05).speed(0.5).launchPitchOffset(-20.0)
                .legacyPotionColors(true)
                // vanilla tracker wire shape: per-tick moves + velocity (a potion's per-tick gravity delta exceeds
                // vanilla's 0.02 send threshold, so vanilla effectively sends velocity every tick too). Silent flight
                // instead leaves clients predicting a potion they can't break: land, freeze, pop late.
                .broadcastMovement(true).velocitySyncInterval(1)
                .entityHit(ProjectileTypeConfig.HitResponse.DESTROY)
                .selfHit(ProjectileTypeConfig.HitResponse.DESTROY)
                .build();
    }

    /**
     * Vanilla 1.8 arrow overrides (on {@link #defaults()}): faster + heavier (speed {@code 3.0}, gravity {@code 0.05}),
     * velocity-based damage ({@code damage = 2.0} per-speed multiplier), sticks in blocks ({@code removeOnBlockHit = false}).
     * Knockback stays shooter-relative (inherited, not {@code PROJECTILE}): a plain arrow knocks the victim away from the
     * shooter, not along flight; Punch rides as the extra-knockback level in that same direction. Damage routes through {@link ProjectileDamage} (a dedicated {@code minecraft:arrow} type is the follow-up).
     */
    public static ProjectileTypeConfig arrow() {
        return ProjectileTypeConfig.builder(Arrow.KEY)
                .gravity(0.05).speed(3.0)
                .damage(2.0).damageType(ProjectileDamage.INSTANCE)
                .removeOnEntityHit(true).removeOnBlockHit(false)
                .invulnHit(ProjectileTypeConfig.HitResponse.DEFLECT, ProjectileTypeConfig.HitResponse.PASS_THROUGH)
                .build();
    }
}
