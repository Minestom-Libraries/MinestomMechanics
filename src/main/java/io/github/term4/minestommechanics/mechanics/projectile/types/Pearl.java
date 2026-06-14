package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.entities.PearlEntity;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Ender pearl throwable: spawns a {@link PearlEntity} that teleports its shooter to the impact (+5 fall damage for
 * a player). Config-free; the vanilla 1.8 self-pass-through (the pearl ignores its own thrower) comes from
 * {@code Vanilla18.pearl()}'s {@code selfHit(PASS_THROUGH)} (the baseline lets you self-hit). Throw + consume wiring is
 * inherited from {@link ThrowableItemType}.
 */
public final class Pearl extends ThrowableItemType {

    public static final Key KEY = Key.key("minecraft:ender_pearl");
    public static final Pearl INSTANCE = new Pearl();

    private Pearl() {
        super(KEY, "Ender Pearl", EntityType.ENDER_PEARL, Material.ENDER_PEARL);
    }

    @Override
    public ProjectileEntity createEntity(@Nullable Entity shooter, ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        return new PearlEntity(shooter, entityType(), snap, effectiveConfig);
    }
}
