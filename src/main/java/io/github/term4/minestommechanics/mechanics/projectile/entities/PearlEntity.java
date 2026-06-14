package io.github.term4.minestommechanics.mechanics.projectile.entities;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.types.fall.FallDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.generic.GenericDamage;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ender pearl projectile: on impact (entity OR block) it teleports its shooter to the impact point and deals
 * 5 fall damage to a player shooter (non-player shooters teleport without damage). Vanilla 1.8 IGNORES hits on the
 * shooter itself - the pearl passes through - wired natively via {@code selfHit(PASS_THROUGH)} (set on
 * {@code Vanilla18.pearl()}). The teleport target is the projectile's pre-move position, matching 1.8
 * {@code EntityEnderPearl.a()} (which runs before {@code locX += motX}). Mirrors 1.8 {@code EntityEnderPearl} /
 * 26.1 {@code ThrownEnderpearl}.
 */
public class PearlEntity extends ManagedProjectile {

    /** Vanilla pearl-landing fall damage dealt to a player shooter. */
    private static final float FALL_DAMAGE = 5.0f;

    public PearlEntity(@Nullable Entity shooter, @NotNull EntityType entityType,
                       ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        super(shooter, entityType, snap, effectiveConfig);
    }

    @Override
    protected void onImpact(@Nullable Entity hitEntity) {
        Entity shooter = getShooter();
        if (shooter == null || shooter.isRemoved()) return;
        // Teleport to the pearl's pre-move position (vanilla a() runs before locX += motX) within its instance, keeping
        // the shooter's OWN view - vanilla enderTeleportTo sets x/y/z only; getPosition() carries the pearl's flight
        // rotation, which must NOT overwrite the thrower's yaw/pitch.
        // TODO(cross-instance): vanilla only teleports when the shooter shares the pearl's world.
        Pos view = shooter.getPosition();
        shooter.teleport(getPosition().withView(view.yaw(), view.pitch()));
        // Vanilla zeroes fallDistance BEFORE dealing the flat 5, so the teleport drop itself adds no extra fall damage.
        FallDamage.resetFallDistance(shooter);
        if (shooter instanceof Player) {
            Services s = services();
            if (s != null && s.damage() != null) {
                // Vanilla 1.8 deals a CONSTANT DamageSource.FALL, 5 (26.1: a dedicated enderPearl source, also 5).
                // GenericDamage + explicit amount stands in (no armor model yet, so numerically == FALL); a dedicated
                // FALL/enderPearl type is the clean follow-up. TODO(verify): plays hurt + respects invul in-game.
                s.damage().apply(DamageSnapshot.of(shooter, GenericDamage.INSTANCE).withAmount(FALL_DAMAGE).withSource(this));
            }
        }
        // TODO(endermite): vanilla 5% endermite spawn on a player teleport (cosmetic) - deferred.
    }
}
