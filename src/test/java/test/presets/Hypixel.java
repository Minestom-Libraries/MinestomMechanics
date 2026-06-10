package test.presets;

import io.github.term4.minestommechanics.Vanilla18;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.tracking.VelocityConfig;
import io.github.term4.minestommechanics.tracking.VelocityRule;

public final class Hypixel {

    private Hypixel() {}

    /** Returns DamageConfig based on Hypixel. */
    public static DamageConfig dmg() {
        return DamageConfig.builder(Vanilla18.dmg())
                .overdamageSilent(true)
                .invulTicks(9)
                .build();
    }

    public static KnockbackConfig kb() {
        //  int buffer = 1; I think this is unnecessary but leaving here just in case

        return KnockbackConfig.builder(Vanilla18.kb())
                // Hypixel's vertical KB does NOT apply vanilla's motY < 0.005 near-zero clamp (the apex
                // "reseed"); with it on, the descending arc folds ~0.003 b/t too low (~11 wire-shorts by the
                // third hit). clampY(0) disables the apex reseed for this arc only; clampX/clampZ stay vanilla.
                .velocity(VelocityRule.simulated(VelocityConfig.builder()
                        .verticalStyle(VelocityRule.ArcStyle.PER_TICK)
                        .clampY(0)
                        .build()))
                //  .sprintBuffer(buffer)
                .extraVertical(0.07)
                .build();
    }
}
