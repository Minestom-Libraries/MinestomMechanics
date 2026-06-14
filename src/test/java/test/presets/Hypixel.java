package test.presets;

import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.tracking.VelocityConfig;
import io.github.term4.minestommechanics.tracking.VelocityRule;

public final class Hypixel {

    private Hypixel() {}

    /**
     * The Hypixel velocity tracking method - set ONCE on a {@code MechanicsProfile.velocity(Hypixel.velocity())}
     * scope; the melee friction fold, the hurt broadcast, and projectile knockback all read it (configs leave
     * their {@code velocity} knob unset).
     */
    public static VelocityRule velocity() {
        return VelocityRule.simulated(arcConfig());
    }

    /** Returns DamageConfig based on Hypixel ({@code hurtKnockback} inherits Vanilla18's zero-impulse config). */
    public static DamageConfig dmg() {
        return DamageConfig.builder(Vanilla18.dmg())
                .overdamageSilent(true)
                .invulTicks(9)
                .build();
    }

    /**
     * Shared simulated-arc knobs. Hypixel's vertical KB does NOT apply vanilla's motY &lt; 0.005 near-zero
     * clamp (the apex "reseed"); with it on, the descending arc folds ~0.003 b/t too low (~11 wire-shorts by
     * the third hit). clampY(0) disables the apex reseed for this arc only; clampX/clampZ stay vanilla.
     * entityPush(false) because Hypixel disables player collision (no server-side push residual to fold).
     */
    private static VelocityConfig arcConfig() {
        return VelocityConfig.builder()
                .verticalStyle(VelocityRule.ArcStyle.PER_TICK)
                .clampY(0)
                .entityPush(false)
                .build();
    }

    public static KnockbackConfig kb() {
        //  int buffer = 1; I think this is unnecessary but leaving here just in case

        return KnockbackConfig.builder(Vanilla18.kb())
                // velocity unset: the fold reads the scoped rule (MechanicsProfile.velocity(Hypixel.velocity())).
                //  .sprintBuffer(buffer)
                .extraVertical(0.07)
                .build();
    }
}
