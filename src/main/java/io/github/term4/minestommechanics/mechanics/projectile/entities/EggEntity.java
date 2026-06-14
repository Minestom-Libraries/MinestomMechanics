package io.github.term4.minestommechanics.mechanics.projectile.entities;

import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.AgeableMobMeta;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Egg projectile: the generic throwable hit (0 damage through the invul gate, knockback, break) plus the vanilla
 * baby-chicken easter egg - a {@code 1/8} chance on impact to spawn a baby chicken (and {@code 1/32} of those
 * spawn 4). Fires on entity AND block impact (the {@link #onImpact} hook). Mirrors 1.8 {@code EntityEgg} /
 * 26.1 {@code ThrownEgg}.
 */
public class EggEntity extends ManagedProjectile {

    public EggEntity(@Nullable Entity shooter, @NotNull EntityType entityType,
                     ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        super(shooter, entityType, snap, effectiveConfig);
    }

    @Override
    protected void onImpact(@Nullable Entity hitEntity) {
        Instance instance = getInstance();
        if (instance == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextInt(8) != 0) return;             // 1/8 spawn chance
        int count = r.nextInt(32) == 0 ? 4 : 1;    // 1/32 of those spawn 4
        for (int i = 0; i < count; i++) {
            Entity chicken = new Entity(EntityType.CHICKEN);
            if (chicken.getEntityMeta() instanceof AgeableMobMeta age) age.setBaby(true);
            chicken.setInstance(instance, getPosition().withPitch(0f));
        }
    }
}
