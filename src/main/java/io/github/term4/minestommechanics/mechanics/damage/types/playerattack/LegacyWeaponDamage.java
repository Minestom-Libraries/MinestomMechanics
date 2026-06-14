package io.github.term4.minestommechanics.mechanics.damage.types.playerattack;

import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static java.util.Map.entry;

/**
 * The 1.8 weapon damage table: total melee damage per held item, including the player's base
 * {@code 1.0} attack-damage attribute (vanilla 1.8: base 1.0 + the item's attack-damage modifier;
 * sword modifier = 3 + material bonus, axe = 2 + bonus, pickaxe = 1 + bonus, shovel = 0 + bonus,
 * with material bonuses wood/gold 0, stone 1, iron 2, diamond 3). Anything not listed - fist,
 * blocks, hoes - deals {@link #FIST_DAMAGE}.
 *
 * <p>This backs the default melee {@code baseAmount}; servers override it per item (or disable the
 * table) via {@code PlayerAttackConfig.builder().baseAmount(...)}. Enchant/effect/armor math is
 * deliberately absent - the future attribute system injects there.
 */
public final class LegacyWeaponDamage {

    /** Bare-hand damage (the 1.8 base attack-damage attribute). */
    public static final double FIST_DAMAGE = 1.0;

    // Netherite is post-1.8; kept one material step above diamond so modern items stay sensible.
    private static final Map<Material, Double> TABLE = Map.ofEntries(
            // Swords: 4 / 4 / 5 / 6 / 7
            entry(Material.WOODEN_SWORD, 4.0),
            entry(Material.GOLDEN_SWORD, 4.0),
            entry(Material.STONE_SWORD, 5.0),
            entry(Material.IRON_SWORD, 6.0),
            entry(Material.DIAMOND_SWORD, 7.0),
            entry(Material.NETHERITE_SWORD, 8.0),
            // Axes: 3 / 3 / 4 / 5 / 6
            entry(Material.WOODEN_AXE, 3.0),
            entry(Material.GOLDEN_AXE, 3.0),
            entry(Material.STONE_AXE, 4.0),
            entry(Material.IRON_AXE, 5.0),
            entry(Material.DIAMOND_AXE, 6.0),
            entry(Material.NETHERITE_AXE, 7.0),
            // Pickaxes: 2 / 2 / 3 / 4 / 5
            entry(Material.WOODEN_PICKAXE, 2.0),
            entry(Material.GOLDEN_PICKAXE, 2.0),
            entry(Material.STONE_PICKAXE, 3.0),
            entry(Material.IRON_PICKAXE, 4.0),
            entry(Material.DIAMOND_PICKAXE, 5.0),
            entry(Material.NETHERITE_PICKAXE, 6.0),
            // Shovels: 1 / 1 / 2 / 3 / 4
            entry(Material.WOODEN_SHOVEL, 1.0),
            entry(Material.GOLDEN_SHOVEL, 1.0),
            entry(Material.STONE_SHOVEL, 2.0),
            entry(Material.IRON_SHOVEL, 3.0),
            entry(Material.DIAMOND_SHOVEL, 4.0),
            entry(Material.NETHERITE_SHOVEL, 5.0)
    );

    private LegacyWeaponDamage() {}

    /** 1.8 attack damage for the held item; {@code null}/air/unlisted items deal {@link #FIST_DAMAGE}. */
    public static double damage(@Nullable ItemStack item) {
        if (item == null || item.isAir()) return FIST_DAMAGE;
        Double v = TABLE.get(item.material());
        return v != null ? v : FIST_DAMAGE;
    }

    /**
     * Default melee {@code baseAmount}: the snapshot's {@link DamageContext#item() item} (falling
     * back to the source's main hand) resolved through the 1.8 table.
     */
    public static Double baseAmount(DamageContext ctx) {
        ItemStack item = ctx.item();
        if (item == null && ctx.snap().source() instanceof LivingEntity le) item = le.getItemInMainHand();
        return damage(item);
    }
}
