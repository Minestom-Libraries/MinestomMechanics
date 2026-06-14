package io.github.term4.minestommechanics.mechanics.damage.types.playerattack;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Entity;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The melee ({@code minecraft:player_attack}) damage type. Crit is <em>determined</em> in the attack
 * layer ({@code AttackEvent#critical()}); this type owns the melee-specific crit multiplier and builds
 * the {@link DamageSnapshot} (with itself as the type) that the caller applies. Its tunables come from
 * the active {@code DamageConfig} ({@link PlayerAttackConfig} registered via {@code typeConfigs(...)}),
 * falling back to {@link #defaultConfig()}.
 */
public final class PlayerAttack extends DamageType {

    public static final Key KEY = Key.key("minecraft:player_attack");
    /** Builder defaults: 1.8 weapon-table base amount, 1.5x crit. */
    private static final PlayerAttackConfig DEFAULT = PlayerAttackConfig.builder().build();
    public static final PlayerAttack INSTANCE = new PlayerAttack();

    private PlayerAttack() {
        super(KEY, "Player Attack", VanillaTypes.PLAYER_ATTACK, DEFAULT);
    }

    /**
     * Builds a melee damage snapshot for a hit with {@code item} (the attacking weapon - vanilla 1.8
     * computes the amount from it, see {@link LegacyWeaponDamage}), applying the crit multiplier when
     * {@code critical} (vanilla order: crit multiplies the weapon-inclusive amount). Resolves the
     * active {@link PlayerAttackConfig}'s {@code baseAmount} and {@code critMultiplier} against a
     * preliminary {@link DamageContext} (so both may be context-aware lambdas), bakes the final
     * amount, and returns the snapshot the caller applies via {@code DamageSystem#apply}.
     */
    public DamageSnapshot snapshot(Entity attacker, Entity target, boolean critical, @Nullable ItemStack item,
                                   Services services) {
        DamageSnapshot prelim = DamageSnapshot.of(target, this).withSource(attacker).withItem(item);
        // Victim-scoped chain when the damage system is present, so per-profile melee tables resolve.
        DamageSystem dmg = services != null ? services.damage() : null;
        DamageContext ctx = dmg != null ? dmg.contextFor(prelim) : DamageContext.of(prelim, services);
        PlayerAttackConfig cfg = ctx.typeConfig() instanceof PlayerAttackConfig p ? p : DEFAULT;
        Double base = cfg.baseAmount(ctx);
        float amount = base != null ? base.floatValue() : 0f;
        if (critical) {
            Double crit = cfg.critMultiplier(ctx);
            if (crit != null) amount *= crit.floatValue();
        }
        return prelim.withAmount(amount);
    }
}
