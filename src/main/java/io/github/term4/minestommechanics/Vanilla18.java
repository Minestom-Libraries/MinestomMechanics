package io.github.term4.minestommechanics;

import io.github.term4.echofix.EchoFixPlayer;
import io.github.term4.minestommechanics.api.event.AttackEvent;
import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.playerattack.PlayerAttack;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSnapshot;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import net.minestom.server.entity.LivingEntity;

/** Preconfigured vanilla 1.8 values. Use these as defaults when resolving nullable configs. */
public final class Vanilla18 {

    private Vanilla18() {}

    /** Returns an AttackConfig with vanilla 1.8 behavior. */
    public static AttackConfig atk() {
        return AttackConfig.builder()
                .enabled(true)
                .ruleset(legacyAttack())
                .criticalRule(AttackEvent.CriticalRule.vanilla())
                .build();
    }

    /** Returns a DamageConfig with vanilla 1.8 values. */
    public static DamageConfig dmg() {
        return DamageConfig.builder()
                .invulTicks(10)
                .enableOverdamage(true)
                .build();
    }

    /**
     * Returns a KnockbackConfig with all vanilla 1.8 values set (no nulls). TODO(modern): 1.20+ KB differs -
     * grounded-only vertical fold, vertical add = power (not 0.4), resistance scales instead of gating, 0.003
     * clamp + apex micro-step (see 26.1 {@code LivingEntity.knockback}; decay law unchanged).
     */
    public static KnockbackConfig kb() {
        return KnockbackConfig.builder()
                .sprintBuffer(0)
                .horizontal(0.4)
                .vertical(0.4)
                .extraHorizontal(0.5)
                .extraVertical(0.1)
                .verticalBounds(null, 0.4000000059604645)
                .yawWeight(0.0)
                .extraYawWeight(1.0)
                .pitchWeight(0.0)
                .extraPitchWeight(0.0)
                .heightDelta(0.0)
                .extraHeightDelta(0.0)
                .horizontalCombine(KnockbackConfig.DirectionMode.VECTOR_ADDITION)
                .verticalCombine(KnockbackConfig.DirectionMode.SCALAR)
                .frictionH(2.0)
                .frictionV(2.0)
                .frictionModeH(KnockbackConfig.FrictionMode.DIVISOR)
                .frictionModeV(KnockbackConfig.FrictionMode.DIVISOR)
                .velocity(VelocityRule.simulated())
                .build();
    }

    /** Legacy (pre-1.9) attack ruleset: applies melee damage, knockback, and resets attacker sprint. */
    public static AttackEvent.AttackRule.Ruleset legacyAttack() {
        return LegacyAttack::new;
    }

    /** Handles legacy (pre-1.9) combat attacks (damage, knockback, sprint reset). */
    private record LegacyAttack(Services services) implements AttackEvent.AttackRule {
        @Override public void processAttack(AttackEvent event) {
            // 1. Damage (crit determined in the attack layer; melee damage type builds the snapshot;
            //    self-gates on the damage window, including the overdamage replacement rule)
            DamageSystem.HitResult result = DamageSystem.HitResult.FULL_HIT; // no damage system -> nothing absorbs the hit
            DamageSystem dmg = services.damage();
            if (dmg != null && event.target() != null) {
                DamageSnapshot snap = PlayerAttack.INSTANCE.snapshot(event.attacker(), event.target(), event.critical(), services);
                result = dmg.apply(snap);
            }
            // 2. Knockback - fresh hits only, exactly vanilla: EntityLiving.damageEntity applies base KB inside
            //    if (flag), and an overdamage replacement sets flag = false. TODO(vanilla nuance): the sprint /
            //    KB-enchant EXTRA knockback (entity.g in EntityHuman.attack) does fire on OVERDAMAGE; our pipeline
            //    computes base+extra together, so replacements currently skip both.
            //    Null config lets the system resolve its chain: victim's scoped profile -> install config.
            KnockbackSystem kb = services.knockback();
            if (result == DamageSystem.HitResult.FULL_HIT && kb != null) {
                var kbSnap = new KnockbackSnapshot(event.target(), event.cause(), event.attacker(), null, null, null);
                kb.apply(kbSnap);
            }
            // 3. Reset attacker sprint - any landed hit (vanilla gates on damageEntity's result, so an
            //    overdamage replacement resets sprint while an absorbed hit keeps it)
            if (result.landed() && event.attacker() instanceof LivingEntity le) {
                if (le instanceof EchoFixPlayer efp) {
                    efp.suppressSelf(() -> efp.setSprinting(false));
                } else {
                    le.setSprinting(false);
                }
            }
        }
    }
}
