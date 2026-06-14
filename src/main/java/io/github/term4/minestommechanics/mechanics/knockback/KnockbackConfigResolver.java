package io.github.term4.minestommechanics.mechanics.knockback;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.tracking.MotionTracker;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.tracking.VelocityContext;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Resolves KnockbackConfig with context into plain values. */
public final class KnockbackConfigResolver {

    private KnockbackConfigResolver() {}

    public record KnockbackContext(KnockbackSnapshot snap, Services services,
                                   @Nullable VelocityRule resolvedVelocity) {
        public static KnockbackContext of(KnockbackSnapshot snap, Services services) {
            return new KnockbackContext(snap, services, null);
        }
        /**
         * Derives a context carrying the velocity rule the calculator resolved for this hit (config override
         * -&gt; the victim's scoped profile -&gt; {@link VelocityRule#DEFAULT}), so a {@link KnockbackComponent}
         * reads the SAME velocity the friction fold used via {@link #victimVelocity()} - instead of hard-pinning
         * a rule onto the config just to keep a component consistent with the fold.
         */
        public KnockbackContext withVelocity(@Nullable VelocityRule rule) {
            return new KnockbackContext(snap, services, rule);
        }
        public boolean victimOnGround() {
            return MotionTracker.onGround(snap.target());
        }
        public boolean sprint() {
            var a = snap.source();
            return a instanceof Player p && services.sprintTracker() != null
                && SprintTracker.wasRecentlySprinting(services.sprintTracker(), p, 0);
        }
        /**
         * The effective victim-velocity rule for this hit: the rule {@link #withVelocity threaded} by the
         * calculator when present, else resolved standalone (this config's {@code velocity} -&gt; the victim's
         * scoped {@link io.github.term4.minestommechanics.MechanicsProfile#velocity() profile velocity} -&gt;
         * {@link VelocityRule#DEFAULT}). The velocity tracking method is a single knob - set it once on a profile
         * scope, not per config.
         */
        public VelocityRule velocityRule() {
            if (resolvedVelocity != null) return resolvedVelocity;
            KnockbackConfig cfg = snap.config();
            VelocityRule rule = cfg != null && cfg.velocity != null ? cfg.velocity.resolve(this) : null;
            if (rule == null && services != null) rule = services.profiles().velocityFor(snap.target());
            return rule != null ? rule : VelocityRule.DEFAULT;
        }
        /**
         * The resolved victim velocity (blocks/tick) - the same value the friction fold folds in - by estimating
         * {@link #velocityRule()} for the target ({@link Vec#ZERO} when there is no target). Lets a custom
         * {@link KnockbackComponent} (e.g. a sprint-aware axial drag) read the victim's velocity without pinning a
         * rule onto the config.
         */
        public Vec victimVelocity() {
            Entity t = snap.target();
            if (t == null) return Vec.ZERO;
            return velocityRule().estimate(VelocityContext.of(t, services != null ? services.sprintTracker() : null));
        }
    }

    public static ResolvedKnockbackConfig resolve(KnockbackConfig config, KnockbackContext ctx) {
        KnockbackConfig cfg = config;
        if (cfg.subConfig != null) {
            KnockbackConfig sub = cfg.subConfig.apply(ctx);
            if (sub != null) cfg = sub.fromBase(cfg);
        }
        // No knockback-level invul window (neither has vanilla - KB gating is the attack processor's job).
        return new ResolvedKnockbackConfig(
                resolve(cfg.sprintBuffer, ctx),
                resolve(cfg.horizontal, ctx),
                resolve(cfg.vertical, ctx),
                resolve(cfg.extraHorizontal, ctx),
                resolve(cfg.extraVertical, ctx),
                resolve(cfg.horizontalBounds, ctx),
                resolve(cfg.verticalBounds, ctx),
                resolve(cfg.extraHorizontalBounds, ctx),
                resolve(cfg.extraVerticalBounds, ctx),
                resolve(cfg.yawWeight, ctx),
                resolve(cfg.extraYawWeight, ctx),
                resolve(cfg.pitchWeight, ctx),
                resolve(cfg.extraPitchWeight, ctx),
                resolve(cfg.heightDelta, ctx),
                resolve(cfg.extraHeightDelta, ctx),
                resolve(cfg.horizontalCombine, ctx),
                resolve(cfg.verticalCombine, ctx),
                resolve(cfg.frictionH, ctx),
                resolve(cfg.frictionV, ctx),
                resolve(cfg.frictionModeH, ctx),
                resolve(cfg.frictionModeV, ctx),
                resolve(cfg.velocity, ctx),
                resolve(cfg.quantizeVelocity, ctx),
                cfg.customComponents
        );
    }

    private static <T> T resolve(@Nullable FieldValue<KnockbackContext, T> fv, KnockbackContext ctx) {
        return fv != null ? fv.resolve(ctx) : null;
    }

    /** Resolved config with plain values. Used by KnockbackCalculator. */
    public record ResolvedKnockbackConfig(
            @Nullable Integer sprintBuffer,
            @Nullable Double horizontal,
            @Nullable Double vertical,
            @Nullable Double extraHorizontal,
            @Nullable Double extraVertical,
            @Nullable KnockbackConfig.Bounds horizontalBounds,
            @Nullable KnockbackConfig.Bounds verticalBounds,
            @Nullable KnockbackConfig.Bounds extraHorizontalBounds,
            @Nullable KnockbackConfig.Bounds extraVerticalBounds,
            @Nullable Double yawWeight,
            @Nullable Double extraYawWeight,
            @Nullable Double pitchWeight,
            @Nullable Double extraPitchWeight,
            @Nullable Double heightDelta,
            @Nullable Double extraHeightDelta,
            @Nullable KnockbackConfig.DirectionMode horizontalCombine,
            @Nullable KnockbackConfig.DirectionMode verticalCombine,
            @Nullable Double frictionH,
            @Nullable Double frictionV,
            @Nullable KnockbackConfig.FrictionMode frictionModeH,
            @Nullable KnockbackConfig.FrictionMode frictionModeV,
            @Nullable VelocityRule velocity,
            @Nullable Boolean quantizeVelocity,
            @Nullable List<KnockbackComponent> customComponents
    ) {}
}
