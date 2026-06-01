package test.presets;

import io.github.term4.minestommechanics.Vanilla18;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;

public final class Hypixel {

    private Hypixel() {}

    public static KnockbackConfig kb() {
        int buffer = 1;

        return KnockbackConfig.builder(Vanilla18.kb())
                .velocityModeH(KnockbackConfig.VelocityMethod.INPUT)
                .velocityModeV(KnockbackConfig.VelocityMethod.GRAVITY_PREDICTED)
                .velocityModeExtraH(KnockbackConfig.VelocityMethod.INPUT)
                .velocityModeExtraV(KnockbackConfig.VelocityMethod.GRAVITY_PREDICTED)
                .gravityPredictScale(0.98)
                .gravityPredictPerTick(0.08)
                .sprintBuffer(buffer)
                .extraVertical(0.07)
                .verticalBounds(null, 0.40)
                .frictionH(1.0)
                .frictionV(2.0)
                .frictionExtraH(1.0)
                .frictionExtraV(2.0)
                .build();
    }
}
