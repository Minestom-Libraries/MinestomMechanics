package io.github.term4.minestommechanics.mechanics.projectile.entities;

import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ResolvedHit;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.projectile.AbstractArrowMeta;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.CollectItemPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Arrow projectile: vanilla velocity-based damage ({@code ceil(speed * perSpeed) (+ crit bonus)}, perSpeed =
 * {@code hit.damage()} = 2.0), sticks in blocks (frozen movement + the vanilla-style periodic re-sync from
 * {@link ProjectileEntity}) instead of breaking, and can be picked up while stuck. {@link #setCritical} (set by the
 * bow at full draw) adds the random crit bonus + the client crit particles. Knockback / damage type / stick come
 * from the config ({@code Vanilla18.arrow()}). Pickup respects a {@link Pickup} mode + the 7-tick shake delay. TODO:
 * Power/Punch/Flame enchants, a dedicated arrow damage type.
 */
public class ArrowEntity extends ManagedProjectile {

    /** Vanilla pickup cooldown ({@code EntityArrow.shake}): the arrow shakes for 7 ticks after sticking and can't be
     *  collected until it ends. */
    private static final int SHAKE_TICKS = 7;

    // Pickup geometry (configurable via ProjectileTypeConfig.pickupBox, stamped at launch; default vanilla). An arrow
    // is collected when its boxWidth x boxHeight box intersects a player's bbox inflated (inflateH, inflateV, inflateH).
    // Both 1.8 (EntityHuman: grow(1,0.5,1)) and 26.1 (Player#aiStep: inflate(1,0.5,1)) use the same inflation; the arrow
    // box is 0.5 x 0.5 (setSize) even though our COLLISION box is 0 (F1).
    private double pickupInflateH = ProjectileTypeConfig.PickupBox.VANILLA.inflateH();
    private double pickupInflateV = ProjectileTypeConfig.PickupBox.VANILLA.inflateV();
    private double pickupBoxHalfWidth = ProjectileTypeConfig.PickupBox.VANILLA.boxWidth() / 2;
    private double pickupBoxHeight = ProjectileTypeConfig.PickupBox.VANILLA.boxHeight();
    /** Broad-phase player scan radius, DERIVED from the geometry. Vanilla has NO scan radius - it iterates players in
     *  the player's grown box; Minestom's entity query is radius-based, so this is only a spatial pre-filter that must
     *  cover the geometry, after which {@link #withinPickupBox} is the exact 1:1 AABB test. */
    private double pickupScanRange = computeScanRange(ProjectileTypeConfig.PickupBox.VANILLA);

    private boolean critical;
    /** Who may collect this arrow once stuck (vanilla {@code EntityArrow.fromPlayer}); the bow sets it per gamemode. */
    private Pickup pickup = Pickup.ALLOWED;
    /** Remaining pickup-cooldown ticks after sticking (vanilla shake). */
    private int shake;

    /** Who may pick up a stuck arrow - mirrors vanilla {@code AbstractArrow.Pickup} / 1.8 {@code EntityArrow.fromPlayer}:
     *  {@link #DISALLOWED} none, {@link #ALLOWED} any player (survival gets the arrow item), {@link #CREATIVE_ONLY}
     *  only creative players (no item). */
    public enum Pickup { DISALLOWED, ALLOWED, CREATIVE_ONLY }

    public ArrowEntity(@Nullable Entity shooter, @NotNull EntityType entityType,
                       ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        super(shooter, entityType, snap, effectiveConfig);
    }

    /** Marks the arrow critical (full draw): extra random damage + the client crit-particle trail. */
    public void setCritical(boolean critical) {
        this.critical = critical;
        if (getEntityMeta() instanceof AbstractArrowMeta meta) meta.setCritical(critical);
    }

    /** Sets who may collect this arrow (the bow passes {@link Pickup#ALLOWED} survival / {@link Pickup#CREATIVE_ONLY} creative). */
    public void setPickup(Pickup pickup) { this.pickup = pickup; }

    /** Stamps the pickup geometry (launcher applies the resolved config); recomputes the derived scan radius. */
    public void setPickupBox(ProjectileTypeConfig.PickupBox box) {
        this.pickupInflateH = box.inflateH();
        this.pickupInflateV = box.inflateV();
        this.pickupBoxHalfWidth = box.boxWidth() / 2;
        this.pickupBoxHeight = box.boxHeight();
        this.pickupScanRange = computeScanRange(box);
    }

    /** Max arrow-to-player-feet distance at which the pickup boxes can still overlap (+ a margin), from the geometry +
     *  a conservative player box (0.3 half-width, 2.0 tall). Only bounds the spatial pre-filter; {@link #withinPickupBox}
     *  is the exact decision. */
    private static double computeScanRange(ProjectileTypeConfig.PickupBox box) {
        double maxH = box.inflateH() + 0.3 + box.boxWidth() / 2;
        double maxV = Math.max(box.inflateV() + box.boxHeight(), 2.0 + box.inflateV());
        return Math.sqrt(maxH * maxH + maxV * maxV + maxH * maxH) + 0.25;
    }

    @Override
    protected float hitDamage(ResolvedHit hit, @NotNull Entity target) {
        // Vanilla EntityArrow: i = ceil(motionLength * this.damage); if (critical) i += rand(i/2 + 2).
        int dmg = (int) Math.ceil(velocityBt.length() * hit.damage());
        if (dmg < 0) dmg = 0;
        if (critical) dmg += ThreadLocalRandom.current().nextInt(dmg / 2 + 2);
        return dmg;
    }

    /** Starts the 7-tick pickup cooldown when the arrow sticks in a block, and flags inGround for new viewers. */
    @Override
    protected boolean onStuck() {
        shake = SHAKE_TICKS;
        if (getEntityMeta() instanceof AbstractArrowMeta meta) meta.setInGround(true);
        return super.onStuck();
    }

    /** Clears the inGround flag when the block is broken out from under the arrow (resume flight on all clients). */
    @Override
    protected void onUnstuck() {
        if (getEntityMeta() instanceof AbstractArrowMeta meta) meta.setInGround(false);
        super.onUnstuck();
    }

    @Override
    protected void updateProjectile(long time) {
        if (!isStuck()) return;
        if (shake > 0) { shake--; return; } // vanilla pickup delay: no collecting while the arrow is still shaking
        if (pickup == Pickup.DISALLOWED) return;
        var instance = getInstance();
        if (instance == null) return;
        Pos arrow = getPosition();
        // Query ONLY nearby players (EntityTracker PLAYERS target - no all-entity sweep), then test the exact vanilla
        // box intersection AND who may collect (pickup mode). The broad-phase radius is just the spatial pre-filter.
        Player[] collected = {null};
        instance.getEntityTracker().nearbyEntities(arrow, pickupScanRange, EntityTracker.Target.PLAYERS, p -> {
            if (collected[0] == null && canCollect(p) && withinPickupBox(arrow, p)) collected[0] = p;
        });
        Player p = collected[0];
        if (p == null) return;
        // Vanilla: ALLOWED gives a survival collector the arrow item; CREATIVE_ONLY (or a creative collector) takes none.
        // (TODO: inventory-full = no pickup; offhand vs inventory; "random.pop" sound.)
        if (pickup == Pickup.ALLOWED && p.getGameMode() != GameMode.CREATIVE) p.getInventory().addItemStack(ItemStack.of(Material.ARROW));
        // Vanilla pickup animation (EntityArrow#d -> player.take): the arrow visibly flies into the collector before
        // it is removed. Sent to viewers + the collector; remove() right after sends the destroy.
        sendPacketToViewersAndSelf(new CollectItemPacket(getEntityId(), p.getEntityId(), 1));
        remove();
    }

    /** Whether {@code p} may collect this arrow given its {@link Pickup} mode (vanilla {@code EntityArrow.d} gate). */
    private boolean canCollect(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        return switch (pickup) {
            case DISALLOWED -> false;
            case ALLOWED -> true;
            case CREATIVE_ONLY -> p.getGameMode() == GameMode.CREATIVE;
        };
    }

    /**
     * Vanilla pickup test: does the arrow's {@code boxWidth x boxHeight} box intersect {@code player}'s bounding box
     * inflated by {@code (inflateH, inflateV, inflateH)}? (Standard AABB overlap on all three axes.)
     */
    private boolean withinPickupBox(Pos arrow, Player p) {
        BoundingBox bb = p.getBoundingBox();
        Pos pp = p.getPosition();
        return arrow.x() + pickupBoxHalfWidth >= pp.x() + bb.relativeStart().x() - pickupInflateH
                && arrow.x() - pickupBoxHalfWidth <= pp.x() + bb.relativeEnd().x() + pickupInflateH
                && arrow.y() + pickupBoxHeight >= pp.y() + bb.relativeStart().y() - pickupInflateV
                && arrow.y() <= pp.y() + bb.relativeEnd().y() + pickupInflateV
                && arrow.z() + pickupBoxHalfWidth >= pp.z() + bb.relativeStart().z() - pickupInflateH
                && arrow.z() - pickupBoxHalfWidth <= pp.z() + bb.relativeEnd().z() + pickupInflateH;
    }
}
