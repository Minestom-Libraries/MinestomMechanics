package io.github.term4.minestommechanics.mechanics.explosion;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure per-entity explosion math (shared 1.8/26.1; constants differ: 1.8 = damageConstant 8.0 floored, 26.1 = 7.0).
 * {@code exposure} = the seen-percent raycast, {@code knockbackReduction} = the Blast-Protection / KB-resistance cut (0 = none).
 * Returns the falloff push + the base damage amount.
 */
public final class ExplosionCalculator {

    private ExplosionCalculator() {}

    /** {@code knockback} is {@code null} when the entity sits exactly on the center (no direction). */
    public record Hit(@Nullable Vec knockback, float damage) {}

    /** Vanilla effect on one entity, or {@code null} if outside the {@code 2·power} radius. */
    public static @Nullable Hit compute(@NotNull Point center, float power, @NotNull Point eyeOrigin, double distance,
                                        float exposure, double damageConstant, boolean floorDamage,
                                        double knockbackMultiplier, double knockbackReduction) {
        final double doubleRadius = power * 2.0;
        if (doubleRadius <= 0.0) return null;
        final double dist = distance / doubleRadius;
        if (dist > 1.0) return null;

        final double impact = (1.0 - dist) * exposure;
        final double raw = (impact * impact + impact) / 2.0 * damageConstant * doubleRadius + 1.0;
        final float damage = floorDamage ? (int) raw : (float) raw;

        final double dx = eyeOrigin.x() - center.x();
        final double dy = eyeOrigin.y() - center.y();
        final double dz = eyeOrigin.z() - center.z();
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final Vec knockback = len < 1.0e-7 ? null
                : new Vec(dx / len, dy / len, dz / len).mul(impact * knockbackMultiplier * (1.0 - knockbackReduction));
        return new Hit(knockback, damage);
    }
}
