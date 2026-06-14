package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.entities.EggEntity;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Egg throwable: like the snowball (config-free, 0 damage, breaks on hit) but spawns an {@link EggEntity} so the
 * vanilla {@code 1/8} baby-chicken-on-impact easter egg runs. Throw + consume wiring is inherited from
 * {@link ThrowableItemType}.
 */
public final class Egg extends ThrowableItemType {

    public static final Key KEY = Key.key("minecraft:egg");
    public static final Egg INSTANCE = new Egg();

    private Egg() {
        super(KEY, "Egg", EntityType.EGG, Material.EGG);
    }

    @Override
    public ProjectileEntity createEntity(@Nullable Entity shooter, ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        return new EggEntity(shooter, entityType(), snap, effectiveConfig);
    }
}
