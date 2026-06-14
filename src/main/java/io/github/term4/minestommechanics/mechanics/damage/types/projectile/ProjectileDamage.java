package io.github.term4.minestommechanics.mechanics.damage.types.projectile;

import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import net.kyori.adventure.key.Key;

/**
 * Thrown-projectile damage ({@code minecraft:thrown}): snowball / egg hits. Vanilla deals {@code 0} damage but
 * the hit still goes through {@code damageEntity} - it plays the hurt animation and opens the damage-invul
 * window, which is the <em>gate</em> that rejects further projectile hits while the victim is invulnerable. So
 * {@code baseAmount = 0} but {@code triggersInvul} stays on; {@code DamageSystem} lets a zero-damage hit land
 * precisely because it triggers invul. The directional knockback rides on the {@code KnockbackSystem} after a
 * landed hit (the type counts as {@code knockbackOwnsVelocity}, so no separate hurt-velocity broadcast).
 */
public final class ProjectileDamage extends DamageType {

    public static final Key KEY = Key.key("minecraft:thrown");
    public static final ProjectileDamage INSTANCE = new ProjectileDamage();

    private ProjectileDamage() {
        super(KEY, "Thrown", VanillaTypes.GENERIC,
                DamageTypeConfig.builder(KEY).baseAmount(0.0).build());
    }
}
