package io.github.term4.minestommechanics.mechanics;

import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.BurningConfig;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.BurningDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.InFireDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.LavaDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.fall.FallDamageConfig;

/**
 * Modern (26.1+) preset factory — not yet fully implemented. {@link Vanilla18} holds the 1.8 baselines.
 *
 * <p><b>Fall damage</b> ({@code LivingEntity.calculateFallDamage} / {@code calculateFallPower}, 26.1):
 * {@link FallDamageConfig.Formula#MODERN_FLOOR} with {@code threshold}, {@code damageModifier}, and
 * {@code fallDamageMultiplier}. Per-block modifiers are supplied by the
 * {@link io.github.term4.minestommechanics.mechanics.damage.types.fall.FallDamage} producer via
 * {@link io.github.term4.minestommechanics.mechanics.damage.types.fall.FallDetail#damageModifier()}.
 *
 * <p><b>Burning</b>: contact amounts match 1.8; ignite warmup defaults to {@code 3 * invulTicks}
 * ({@link BurningConfig#igniteWarmupInvulMult}) vs {@code 2 * invulTicks} in {@link Vanilla18}.
 * Environmental burning types use
 * {@code "scaling": "when_caused_by_living_non_player"} — no difficulty scaling for players.
 *
 * <p>Still TODO in the producer: {@code FALL_DAMAGE_IMMUNE} tag, Slow Falling, elytra
 * {@code FALL_FLYING}, explosion-impulse grace, hay bale / bed / powder snow / cobweb landing scans.
 *
 * <p><b>Attacker velocity on melee hit</b> ({@code Player.attack} / {@code causeExtraKnockback}, 26.1):
 * both 1.8 and modern only touch the <em>attacker's</em> server-tracked velocity when knockback is actually
 * dealt on a landed hit — horizontal {@code *= 0.6}, Y unchanged, sprint cleared. No change on whiff / invuln /
 * zero-knockback swings.
 * <ul>
 *   <li><b>Sprint knockback gate:</b> 1.8 adds {@code +1} to the knockback counter whenever
 *       {@code isSprinting()} at hit time (no cooldown). 26.1 only adds {@code +0.5F} when sprinting
 *       <em>and</em> {@code attackStrengthScale > 0.9F} (full-strength / post-cooldown swing).</li>
 *   <li><b>Enchant knockback:</b> 1.8 uses integer level {@code i} (enchant + sprint bonus) times
 *       {@code 0.5} horizontal on the victim via {@code entity.g()}. 26.1 uses
 *       {@code getKnockback()} = {@code (ATTACK_KNOCKBACK attribute + enchant) / 2.0F} fed into
 *       {@code LivingEntity.knockback()} (fold-half-then-subtract formula, not additive {@code g()}).</li>
 *   <li><b>Damage prerequisite:</b> 1.8 attacker slowdown requires {@code damageEntity} true.
 *       26.1 requires {@code wasHurt} for normal {@code attack()}; {@code stabAttack()} can still call
 *       {@code causeExtraKnockback} (and thus attacker {@code 0.6} scaling) when {@code dealsKnockback}
 *       even if damage did not land.</li>
 *   <li><b>Sweep:</b> 26.1 sweep hits knock back nearby targets but do not apply a second attacker slowdown.</li>
 * </ul>
 * Not implemented here yet — {@link Vanilla18} is the active 1.8 baseline.
 */
public final class Vanilla {
    private Vanilla() {}

    /** Damage config with modern fall formula and 26.1 burning behaviour. */
    public static DamageConfig dmg() {
        return DamageConfig.builder()
                .typeConfigs(
                        fallDamage(),
                        inFireDamage(),
                        lavaDamage(),
                        burningDamage()
                )
                .build();
    }

    private static FallDamageConfig fallDamage() {
        return FallDamageConfig.builder()
                .formula(FallDamageConfig.Formula.MODERN_FLOOR)
                .threshold(3.0)
                .damageModifier(1.0)
                .fallDamageMultiplier(1.0)
                .build();
    }

    private static BurningConfig inFireDamage() {
        return BurningConfig.builder()
                .key(InFireDamage.KEY)
                .baseAmount(1.0)
                .igniteTicks(160)
                .igniteWarmupInvulMult(3)
                .contactIntervalTicks(1)
                .build();
    }

    private static BurningConfig lavaDamage() {
        return BurningConfig.builder()
                .key(LavaDamage.KEY)
                .baseAmount(4.0)
                .igniteTicks(300)
                .igniteWarmupInvulMult(3)
                .contactIntervalTicks(1)
                .build();
    }

    private static BurningConfig burningDamage() {
        return BurningConfig.builder()
                .key(BurningDamage.KEY)
                .baseAmount(1.0)
                .intervalTicks(20)
                .skipBurnWhileInLava(true)
                .build();
    }
}

