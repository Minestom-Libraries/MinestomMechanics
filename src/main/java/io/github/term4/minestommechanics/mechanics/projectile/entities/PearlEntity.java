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
 * Ender pearl projectile: on impact (entity or block) it teleports the shooter to the impact point and deals 5 fall
 * damage to a player shooter. Vanilla 1.8 ignores hits on the shooter (the pearl passes through), wired via
 * {@code selfHit(PASS_THROUGH)}. The teleport target is the pre-move position (1.8 {@code EntityEnderPearl.a()}).
 */
public class PearlEntity extends ManagedProjectile {

    /** Vanilla pearl-landing fall damage dealt to a player shooter. TODO: make configurable (folds into the dedicated pearl/fall type below). */
    private static final float FALL_DAMAGE = 5.0f;

    /** Whether the pearl teleports its shooter across instances; default false (vanilla = same world only). */
    private boolean crossInstanceTeleport = false;

    public PearlEntity(@Nullable Entity shooter, @NotNull EntityType entityType,
                       ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        super(shooter, entityType, snap, effectiveConfig);
    }

    /**
     * Enables teleporting the shooter even when they've left the pearl's instance (stasis chambers); default false matches
     * vanilla (same-world only). Set per launch off the entity in a {@code ProjectileLaunchEvent} listener.
     */
    public void setCrossInstanceTeleport(boolean v) { this.crossInstanceTeleport = v; }

    @Override
    protected void onImpact(@Nullable Entity hitEntity) {
        Entity shooter = getShooter();
        if (shooter == null || shooter.isRemoved()) return;
        // Vanilla only teleports within the pearl's world (1.8 entityplayer.world == this.world; 26 same dimension, else a
        // portal transition). crossInstanceTeleport (off by default) opts into teleporting a shooter who has since left.
        boolean sameInstance = shooter.getInstance() == getInstance();
        if (!sameInstance && (!crossInstanceTeleport || getInstance() == null)) return;
        // teleport to the pearl's impact position, keeping the shooter's own view (not the pearl's flight rotation)
        Pos view = shooter.getPosition();
        Pos dest = getPosition().withView(view.yaw(), view.pitch());
        if (sameInstance) shooter.teleport(dest);
        else shooter.setInstance(getInstance(), dest);
        // zero fallDistance first so the teleport drop adds no extra fall damage
        FallDamage.resetFallDistance(shooter);
        if (shooter instanceof Player) {
            Services s = services();
            if (s != null && s.damage() != null) {
                // GenericDamage + explicit 5 stands in for the dedicated FALL/enderPearl type (no armor model yet). TODO(verify): hurt + invul in-game
                s.damage().apply(DamageSnapshot.of(shooter, GenericDamage.INSTANCE).withAmount(FALL_DAMAGE).withSource(this));
            }
        }
        // TODO(endermite): vanilla 5% endermite spawn on teleport (cosmetic, deferred)
    }
}
