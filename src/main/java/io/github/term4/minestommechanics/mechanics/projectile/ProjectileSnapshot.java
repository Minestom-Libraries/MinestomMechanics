package io.github.term4.minestommechanics.mechanics.projectile;

import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable input describing a single projectile launch. Mirrors {@code DamageSnapshot} /
 * {@code KnockbackSnapshot}: the producing {@link ProjectileType} (e.g. a bow/throw trigger) builds it and
 * hands it to {@link ProjectileSystem#launch}.
 *
 * @param shooter  the entity launching the projectile (knockback origin, damage source, hit immunity)
 * @param type     the projectile type (snowball, arrow, ...) - its config + entity factory
 * @param item     the item that launched it (snowball stack, bow), for {@code ctx.item()} config lambdas
 * @param power    launch power (throw strength / bow draw, {@code 1.0} baseline) - scales the resolved speed
 * @param spawnPos explicit spawn position override, or {@code null} to compute from the shooter's eye + offset
 * @param velocity explicit initial velocity (b/t) override, or {@code null} to compute from aim + speed + spread
 * @param config   per-launch config override, or {@code null} to resolve the scope chain (profile -> install)
 */
public record ProjectileSnapshot(Entity shooter, ProjectileType type, @Nullable ItemStack item, double power,
                                 @Nullable Pos spawnPos, @Nullable Vec velocity, @Nullable ProjectileConfig config) {

    public static ProjectileSnapshot of(Entity shooter, ProjectileType type) {
        return new ProjectileSnapshot(shooter, type, null, 1.0, null, null, null);
    }

    public ProjectileSnapshot withItem(@Nullable ItemStack i) { return new ProjectileSnapshot(shooter, type, i, power, spawnPos, velocity, config); }
    public ProjectileSnapshot withPower(double p) { return new ProjectileSnapshot(shooter, type, item, p, spawnPos, velocity, config); }
    public ProjectileSnapshot withSpawnPos(@Nullable Pos p) { return new ProjectileSnapshot(shooter, type, item, power, p, velocity, config); }
    public ProjectileSnapshot withVelocity(@Nullable Vec v) { return new ProjectileSnapshot(shooter, type, item, power, spawnPos, v, config); }
    public ProjectileSnapshot withConfig(@Nullable ProjectileConfig c) { return new ProjectileSnapshot(shooter, type, item, power, spawnPos, velocity, c); }
}
