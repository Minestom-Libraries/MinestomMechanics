package io.github.term4.minestommechanics.mechanics.knockback;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.tracking.MotionTracker;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Resolves KnockbackConfig with context into plain values. */
public final class KnockbackConfigResolver {

    private KnockbackConfigResolver() {}

    public record KnockbackContext(KnockbackSnapshot snap, Services services) {
        public static KnockbackContext of(KnockbackSnapshot snap, Services services) {
            return new KnockbackContext(snap, services);
        }
        public boolean victimOnGround() {
            return MotionTracker.onGround(snap.target());
        }
        public boolean sprint() {
            var a = snap.source();
            return a instanceof Player p && services.sprintTracker() != null
                && SprintTracker.wasRecentlySprinting(services.sprintTracker(), p, 0);
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
            @Nullable List<KnockbackComponent> customComponents
    ) {}
}
