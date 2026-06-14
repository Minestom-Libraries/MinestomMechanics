package io.github.term4.minestommechanics.mechanics.projectile;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a {@link ProjectileConfig} + snapshot into the plain values a projectile needs, in TWO phases against the
 * same {@link ProjectileContext}:
 * <ul>
 *   <li>{@link #resolveFlight} at LAUNCH - the spawn + physics knobs (bbox, aerodynamics, spawn offsets, speed,
 *       spread, momentum, shooter immunity, sync). The target is unknown here.</li>
 *   <li>{@link #resolveHit} at IMPACT - the hit knobs (does it hit, damage, knockback, removal), resolved against a
 *       context that carries the struck {@link ProjectileContext#target()} and {@link ProjectileContext#throwOrigin()},
 *       so a config lambda can branch on {@link ProjectileContext#isSelfHit()} or read the throw-time snapshot. Hits
 *       are rare (not per-tick), so resolving them late is cheap and keeps self-hit / throw-time behavior in plain
 *       config instead of the event API.</li>
 * </ul>
 * The merged {@link ProjectileTypeConfig} ({@link ProjectileContext#typeConfig()}) is computed once at launch and
 * reused for the impact resolution.
 */
public final class ProjectileConfigResolver {

    private ProjectileConfigResolver() {}

    /**
     * The context the per-type projectile {@code FieldValue}s resolve against. Launch-time use carries just the
     * snapshot + services; {@link #atHit} adds the impact fields ({@link #target}, {@link #throwOrigin},
     * {@link #hitPos}) for resolving the hit knobs.
     */
    public record ProjectileContext(ProjectileSnapshot snap, @Nullable Services services,
                                    @Nullable Entity target, @Nullable Pos throwOrigin, @Nullable Point hitPos) {
        public static ProjectileContext of(ProjectileSnapshot snap, @Nullable Services services) {
            return new ProjectileContext(snap, services, null, null, null);
        }

        /** Derives the impact-time context (target / throwOrigin / hitPos set) for resolving the hit knobs. */
        public ProjectileContext atHit(@Nullable Entity target, @Nullable Pos throwOrigin, @Nullable Point hitPos) {
            return new ProjectileContext(snap, services, target, throwOrigin, hitPos);
        }

        public Entity shooter() { return snap.shooter(); }
        public @Nullable ItemStack item() { return snap.item(); }
        public double power() { return snap.power(); }

        /** The struck entity (impact only), or {@code null} for a block hit / at launch. */
        public @Nullable Entity target() { return target; }
        /** Whether the struck entity is the shooter itself - the native self-vs-other test for a hit lambda. */
        public boolean isSelfHit() { return target != null && target == snap.shooter(); }
        /** The shooter's position + view at THROW time (impact only); see {@code ProjectileEntity.getShooterOriginPos}. */
        public @Nullable Pos throwOrigin() { return throwOrigin; }
        /** The impact position (impact only). */
        public @Nullable Point hitPos() { return hitPos; }

        /**
         * Effective per-type config for this launch, layered highest-first: the active {@link ProjectileConfig}'s
         * per-type override (snapshot config, else the install config) -&gt; that config's generic
         * {@link ProjectileConfig#defaults() defaults} -&gt; the type's intrinsic
         * {@link io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileType#defaultConfig()},
         * with any context-aware {@code subConfig} overlay layered on top.
         */
        public ProjectileTypeConfig typeConfig() {
            ProjectileConfig cfg = snap.config();
            if (cfg == null && services != null && services.projectiles() != null) cfg = services.projectiles().config();
            ProjectileTypeConfig tc = cfg != null ? cfg.typeConfig(snap.type().key()) : null;
            ProjectileTypeConfig generic = cfg != null ? cfg.defaults() : null;
            ProjectileTypeConfig base = snap.type().defaultConfig();
            if (generic != null) base = generic.fromBase(base);
            if (tc != null) base = tc.fromBase(base);
            if (base.subConfig() != null) {
                ProjectileTypeConfig overlay = base.subConfig().apply(this);
                if (overlay != null) base = overlay.fromBase(base);
            }
            return base;
        }
    }

    /** Resolves the FLIGHT knobs (spawn + physics) at launch from the effective type config. */
    public static ResolvedFlight resolveFlight(ProjectileTypeConfig tc, ProjectileContext ctx) {
        return new ResolvedFlight(
                tc.enabled(ctx),
                or(tc.boundingBox(ctx), POINT_BOX),
                or(tc.gravity(ctx), 0.03),
                or(tc.horizontalDrag(ctx), 0.99),
                or(tc.verticalDrag(ctx), 0.99),
                or(tc.spawnOffsetH(ctx), 0.0),
                or(tc.spawnOffsetV(ctx), 0.0),
                or(tc.spawnOffsetLateral(ctx), 0.0),
                or(tc.speed(ctx), 1.5),
                or(tc.spread(ctx), 0.0),
                or(tc.inheritMomentum(ctx), Boolean.FALSE), // vanilla 1.8 throwables add NO shooter momentum
                or(tc.shooterImmunityTicks(ctx), 5),
                or(tc.entityHitGrow(ctx), 0.3), // vanilla 1.8 Entity{Arrow,Projectile}: target grow 0.3 each side
                or(tc.syncInterval(ctx), 20));
    }

    /** Resolves the HIT knobs at impact from the effective type config against the impact {@code ctx}. */
    public static ResolvedHit resolveHit(ProjectileTypeConfig tc, ProjectileContext ctx) {
        return new ResolvedHit(
                or(tc.selfHit(ctx), ProjectileTypeConfig.HitResponse.HIT),
                tc.knockback(ctx),
                or(tc.knockbackSource(ctx), ProjectileTypeConfig.KnockbackSource.PROJECTILE),
                or(tc.damage(ctx), 0.0),
                tc.damageType(ctx),
                or(tc.removeOnEntityHit(ctx), Boolean.TRUE),
                or(tc.removeOnBlockHit(ctx), Boolean.TRUE),
                or(tc.invulnHit(ctx), ProjectileTypeConfig.HitResponse.DESTROY)); // throwables break on an invuln hit; arrow = DEFLECT
    }

    /** Zero-size box (MinestomPVP's POINT_BOX): collision points resolve exactly on block boundaries (fix ledger F1). */
    private static final BoundingBox POINT_BOX = new BoundingBox(0, 0, 0);

    private static <T> T or(@Nullable T v, T def) { return v != null ? v : def; }

    /** Flight values resolved at launch (spawn + physics). */
    public record ResolvedFlight(
            boolean enabled,
            BoundingBox boundingBox,
            double gravity,
            double horizontalDrag,
            double verticalDrag,
            double spawnOffsetH,
            double spawnOffsetV,
            double spawnOffsetLateral,
            double speed,
            double spread,
            boolean inheritMomentum,
            int shooterImmunityTicks,
            double entityHitGrow,
            int syncInterval
    ) {}

    /** Hit values resolved at impact ({@code knockback}/{@code damageType} nullable). */
    public record ResolvedHit(
            ProjectileTypeConfig.HitResponse selfHit,
            @Nullable KnockbackConfig knockback,
            ProjectileTypeConfig.KnockbackSource knockbackSource,
            double damage,
            @Nullable DamageType damageType,
            boolean removeOnEntityHit,
            boolean removeOnBlockHit,
            ProjectileTypeConfig.HitResponse invulnHit
    ) {}
}
