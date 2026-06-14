package io.github.term4.minestommechanics.mechanics.projectile.types;

import net.kyori.adventure.key.Key;
import net.minestom.server.entity.EntityType;
import net.minestom.server.item.Material;

/**
 * Snowball throwable: launched on a {@link Material#SNOWBALL} use. Config-free (its physics, knockback, and damage
 * live in the preset's {@code ProjectileConfig} - e.g. {@code Vanilla18.projectiles()}); the generic
 * {@link io.github.term4.minestommechanics.mechanics.projectile.entities.ManagedProjectile} handles the hit (0
 * damage through the invul gate, knockback, break on any entity or block hit). Throw + consume wiring is inherited
 * from {@link ThrowableItemType}.
 */
public final class Snowball extends ThrowableItemType {

    public static final Key KEY = Key.key("minecraft:snowball");
    public static final Snowball INSTANCE = new Snowball();

    private Snowball() {
        super(KEY, "Snowball", EntityType.SNOWBALL, Material.SNOWBALL);
    }
}
