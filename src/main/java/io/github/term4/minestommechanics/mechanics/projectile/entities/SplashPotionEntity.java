package io.github.term4.minestommechanics.mechanics.projectile.entities;

import io.github.term4.minestommechanics.mechanics.attribute.catalog.PotionColors;
import io.github.term4.minestommechanics.mechanics.attribute.catalog.VanillaPotions;
import io.github.term4.minestommechanics.mechanics.damage.types.magic.HealOrHarm;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.item.SplashPotionMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.Potion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Splash potion projectile: on impact it applies the item's effect payload to every living entity in the vanilla
 * splash volume, scaled by distance (1.8 {@code EntityPotion.a()}, identical shape in 26): candidates from the impact
 * box grown {@code (4, 2, 4)}, gated on center distance² {@code < 16}, intensity {@code 1 - sqrt(d)/4} (a directly-hit
 * entity gets {@code 1.0} - the 1.8 model; 26 uses box distance instead). Timed effects last
 * {@code (int)(intensity * duration * durationScale + 0.5)} and only apply above 20 ticks; instant ones route through
 * {@link HealOrHarm}. The glass break + particle cloud is level event 2002, per-viewer (see
 * {@link #broadcastSplashEvent}); 26's separate 2007-for-instant variant is skipped - 1.8 has only 2002.
 */
public class SplashPotionEntity extends ManagedProjectile {

    private static final double SPLASH_RANGE_SQ = 16.0;
    private static final int MIN_EFFECT_TICKS = 20;
    private static final int SPLASH_LEVEL_EVENT = 2002;

    /** Whether MODERN viewers get the 1.8 particle palette ({@code legacyPotionColors}); resolved at launch. */
    private final boolean legacyPalette;

    public SplashPotionEntity(@Nullable Entity shooter, @NotNull EntityType entityType,
                              ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        super(shooter, entityType, snap, effectiveConfig);
        ItemStack item = snap.item();
        // the client renders the liquid color from the meta item
        if (item != null) ((SplashPotionMeta) getEntityMeta()).setItem(item);
        Boolean legacy = effectiveConfig.legacyPotionColors != null
                ? effectiveConfig.legacyPotionColors.resolve(ProjectileConfigResolver.ProjectileContext.of(snap, services())) : null;
        this.legacyPalette = Boolean.TRUE.equals(legacy);
    }

    @Override
    protected void onImpact(@Nullable Entity hitEntity) {
        Instance instance = getInstance();
        ItemStack item = ((SplashPotionMeta) getEntityMeta()).getItem();
        if (instance == null) return;
        List<CustomPotionEffect> payload = VanillaPotions.payload(item);
        Point at = getPosition();
        if (!payload.isEmpty()) {
            Float scale = item.get(DataComponents.POTION_DURATION_SCALE);
            splash(instance, at, hitEntity, payload, scale != null ? scale : 1.0f);
        }
        broadcastSplashEvent(item, payload, at);
    }

    /**
     * The glass break + particle cloud (level event 2002), split per viewer: a legacy client reads the event data as a
     * raw 1.8 potion VALUE (Via passes it through untranslated - RGB would be garbage to it), a modern client as an RGB
     * color ({@code legacyPotionColors} picks the palette).
     *
     * <p>TODO: relocate to the vanilla featureset when it lands (with the other absent vanilla broadcasts: sounds,
     * block-break animation, item drops/pickup, crit + potion particles).
     */
    private void broadcastSplashEvent(ItemStack item, List<CustomPotionEffect> payload, Point at) {
        PotionContents contents = item.get(DataComponents.POTION_CONTENTS);
        int color = legacyPalette ? PotionColors.legacyColor(contents, payload) : PotionColors.color(contents, payload);
        int legacyValue = VanillaPotions.legacySplashValue(contents != null ? contents.potion() : null);
        var mm = io.github.term4.minestommechanics.MinestomMechanics.getInstance();
        var clientInfo = mm.isInitialized() ? mm.clientInfo() : null;
        for (Player viewer : getViewers()) {
            boolean legacy = clientInfo != null && clientInfo.isLegacy(viewer);
            viewer.sendPacket(new WorldEventPacket(SPLASH_LEVEL_EVENT, at, legacy ? legacyValue : color, false));
        }
    }

    private void splash(Instance instance, Point at, @Nullable Entity hitEntity,
                        List<CustomPotionEffect> payload, float durationScale) {
        for (Entity entity : instance.getNearbyEntities(at, 8.0)) {
            if (!(entity instanceof LivingEntity living)) continue;
            // vanilla gathers from the impact box grown (4, 2, 4); the y gate is what the box adds over the distance one
            if (Math.abs(living.getPosition().y() - at.y()) > 2.0 + living.getBoundingBox().height()) continue;
            double distSq = living.getPosition().distanceSquared(at);
            if (distSq >= SPLASH_RANGE_SQ && living != hitEntity) continue;
            double intensity = living == hitEntity ? 1.0 : 1.0 - Math.sqrt(distSq) / 4.0;
            for (CustomPotionEffect e : payload) {
                if (HealOrHarm.apply(services(), living, getShooter(), at, e, intensity)) continue;
                int duration = (int) (intensity * e.duration() * durationScale + 0.5);
                if (duration > MIN_EFFECT_TICKS) {
                    VanillaPotions.addEffect(living, new Potion(e.id(), e.amplifier(), duration, potionFlags(e)));
                }
            }
        }
    }

    private static byte potionFlags(CustomPotionEffect e) {
        return (byte) ((e.isAmbient() ? Potion.AMBIENT_FLAG : 0)
                | (e.showParticles() ? Potion.PARTICLES_FLAG : 0)
                | (e.showIcon() ? Potion.ICON_FLAG : 0));
    }
}
