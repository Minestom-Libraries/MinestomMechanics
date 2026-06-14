package io.github.term4.minestommechanics.mechanics.projectile.entities;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.api.event.ProjectileHitEvent;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSnapshot;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ProjectileContext;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ResolvedHit;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Generic config-driven projectile. The HIT knobs are resolved at IMPACT (not launch) against a
 * {@link ProjectileContext} carrying the struck target + throw-time origin, so config lambdas can branch on
 * {@code ctx.isSelfHit()} / {@code ctx.throwOrigin()} without the event API. On an entity hit it applies the resolved
 * damage (via the {@link io.github.term4.minestommechanics.mechanics.damage.DamageSystem}) and knockback (via the
 * {@link KnockbackSystem}), then removes per {@code removeOnEntityHit}; a self-hit answered {@code PASS_THROUGH}
 * passes through (or {@code DEFLECT} bounces off). On a block hit it removes per {@code removeOnBlockHit}. Both fire the cancellable
 * {@link ProjectileHitEvent} first. Types with extra behavior (egg, pearl) override {@link #onImpact}.
 */
public class ManagedProjectile extends ProjectileEntity {

    /** The merged per-type config (FieldValues unresolved); hit knobs resolve from it at impact. */
    private final ProjectileTypeConfig effectiveConfig;
    private final ProjectileSnapshot snap;

    public ManagedProjectile(@Nullable Entity shooter, @NotNull EntityType entityType,
                             ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        super(shooter, entityType);
        this.snap = snap;
        this.effectiveConfig = effectiveConfig;
    }

    /** Resolves the hit knobs at impact: {@code target} is the struck entity, or {@code null} for a block hit. */
    private ResolvedHit resolveHit(@Nullable Entity target) {
        ProjectileContext ctx = ProjectileContext.of(snap, services())
                .atHit(target, getShooterOriginPos(), getPosition());
        return ProjectileConfigResolver.resolveHit(effectiveConfig, ctx);
    }

    @Override
    protected boolean onHit(@NotNull Entity target) {
        ResolvedHit hit = resolveHit(target);
        // Self-hit response (default HIT). Vanilla snowball/egg HIT (you CAN hit yourself); the 1.8 pearl PASS_THROUGHs.
        if (target == shooter) {
            switch (hit.selfHit()) {
                case HIT -> { /* fall through to the normal hit */ }
                case PASS_THROUGH -> { return false; }
                case DEFLECT -> { deflect(); return false; }
                case DESTROY -> { onImpact(target); return true; }
            }
        }

        var ev = new ProjectileHitEvent(this, shooter, target, getPosition(), getShooterOriginPos());
        EventDispatcher.call(ev);
        if (ev.isCancelled()) return false;

        // Route through the DAMAGE system first (vanilla: even a 0-damage thrown hit calls damageEntity). This plays
        // the hurt animation and opens/checks the invul window - the GATE: a hit on an already-invulnerable victim
        // returns BLOCKED (landed = false), so its knockback is suppressed. With no damageType configured there is no
        // gate and the hit always lands.
        boolean landed = true;
        Services s = services();
        if (s != null) {
            DamageType dt = hit.damageType();
            if (dt != null && s.damage() != null) {
                landed = s.damage().apply(DamageSnapshot.of(target, dt)
                        .withSource(shooter).withPoint(getPosition()).withAmount(hitDamage(hit, target))).landed();
            }
            // Knockback (gated by the invul result). PROJECTILE-relative (origin = projectile, dir = flight) or
            // SHOOTER-relative (source = shooter, yaw via the KB config's yawWeight). It owns the velocity broadcast.
            if (landed && hit.knockback() != null && s.knockback() != null) {
                s.knockback().apply(buildKnockback(target, hit.knockbackSource(), hit.knockback()));
            }
        }
        // Hit blocked (the target was invulnerable / creative): the configured invulnHit response. Vanilla arrow =
        // DEFLECT (bounce, motion *= -0.1); throwables = DESTROY (break/effect, like their die() on any hit).
        // PASS_THROUGH keeps flying; DESTROY (and HIT, N/A for a non-landing hit) breaks via the impact path below.
        if (!landed) {
            switch (hit.invulnHit()) {
                case PASS_THROUGH -> { return false; }
                case DEFLECT -> { deflect(); return false; }
                case HIT, DESTROY -> { /* fall through to onImpact + removeOnEntityHit */ }
            }
        }
        onImpact(target);
        return hit.removeOnEntityHit();
    }

    @Override
    protected boolean onStuck() {
        ResolvedHit hit = resolveHit(null);
        var ev = new ProjectileHitEvent(this, shooter, null, getPosition(), getShooterOriginPos());
        EventDispatcher.call(ev);
        if (ev.isCancelled()) return false;
        onImpact(null);
        return hit.removeOnBlockHit();
    }

    /**
     * Type-specific impact effect, fired once a hit lands (entity OR block) and is not cancelled, after the
     * damage/knockback pipeline and before removal. {@code hitEntity} is the struck entity, or {@code null} for a
     * block hit. Override for egg (spawn chicken), ender pearl (teleport the shooter), etc. - both vanilla effects
     * fire on entity and block impact alike. Default: no-op. Branch on {@code hitEntity == getShooter()} for a
     * self-vs-other effect (a self-hit answered {@code PASS_THROUGH}/{@code DEFLECT} never reaches here).
     */
    protected void onImpact(@Nullable Entity hitEntity) {}

    /**
     * The damage to deal to {@code target} on an entity hit. Default: the resolved config {@code damage} (a flat
     * amount). Arrows override this to compute vanilla velocity-based damage ({@code ceil(speed * 2) + crit}).
     */
    protected float hitDamage(ResolvedHit hit, @NotNull Entity target) { return (float) hit.damage(); }

    /**
     * Bounces the projectile off an entity it may not damage (an invuln hit, or a {@code DEFLECT} self-hit) - keep
     * flying, no damage/KB/break. Vanilla 1.8 {@code EntityArrow} else-branch: {@code motX/Y/Z *= -0.1} (reverse +
     * heavy damp) then {@code as = 0} (re-arm shooter immunity so the bounced-back arrow can't instantly re-hit the
     * shooter / loop on a self-deflect).
     */
    protected void deflect() {
        setVelocityBt(velocityBt.mul(-0.1));
        rearmShooterImmunity();
        setDeflected();
    }

    /** Builds the hit knockback snapshot for the given {@link ProjectileTypeConfig.KnockbackSource} + config. */
    private KnockbackSnapshot buildKnockback(@NotNull Entity target, ProjectileTypeConfig.KnockbackSource source, KnockbackConfig kb) {
        if (shooter != null && source == ProjectileTypeConfig.KnockbackSource.SHOOTER) {
            // Source = shooter (like melee): the KnockbackCalculator reads the shooter's position + look, so the KB
            // config's yawWeight chooses shooter -> victim (0) vs the shooter's yaw (1) - no special source needed.
            return new KnockbackSnapshot(target, false, shooter, null, null, kb);
        }
        // PROJECTILE (or no shooter): origin = projectile position, direction = horizontal flight path.
        Vec h = new Vec(velocityBt.x(), 0, velocityBt.z());
        Vec flightDir = h.lengthSquared() < 1e-9 ? null : h.normalize();
        return new KnockbackSnapshot(target, false, null, getPosition(), flightDir, kb);
    }

    /** Live services lookup (the systems are install-time singletons); null-tolerant if none installed. */
    protected @Nullable Services services() {
        var mm = io.github.term4.minestommechanics.MinestomMechanics.getInstance();
        return mm.isInitialized() ? mm.services() : null;
    }
}
