package io.github.term4.minestommechanics.mechanics.knockback;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.tracking.VelocityContext;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import io.github.term4.minestommechanics.util.Directions;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** Computes the knockback vector for a snapshot: directions, strengths, friction fold, bounds, components. */
// TODO(stages): make each built-in stage a replaceable strategy (the VelocityRule/CriticalRule pattern,
//  config knobs like frictionRule/combineRule/boundsRule with the current math as DEFAULT). Custom
//  components are append-only POST-stages - they cannot run between built-ins, which forces presets to
//  zero a knob and re-emulate the stage (Minemen frictionH=0 + axialFriction) and caused the range-cap vs
//  axial-drag ordering bug. Same idea applies to DamageCalculator's stages.
public final class KnockbackCalculator {

    private final Services services;
    private final KnockbackConfig defaults;

    private final double tps = ServerFlag.SERVER_TICKS_PER_SECOND;

    public KnockbackCalculator(Services services, KnockbackConfig defaults) {
        this.services = services;
        this.defaults = defaults;
    }

    /**
     * Resolves the effective plain values for a snapshot: its config (else the calculator defaults) merged over
     * the defaults, resolved against the hit context - the exact values {@link #compute} will use. Cheap; safe to
     * call per hit (backs {@link io.github.term4.minestommechanics.api.event.KnockbackEvent#resolvedConfig()}).
     */
    public KnockbackConfigResolver.ResolvedKnockbackConfig resolveConfig(KnockbackSnapshot snap) {
        KnockbackConfig merged = snap.config() != null ? snap.config().fromBase(defaults) : defaults;
        KnockbackConfigResolver.KnockbackContext ctx = KnockbackConfigResolver.KnockbackContext.of(snap, services);
        return KnockbackConfigResolver.resolve(merged, ctx);
    }

    public @Nullable Vec compute(KnockbackSnapshot snap) {
        KnockbackConfig base = snap.config();
        if (base == null) return null;

        KnockbackConfig merged = base.fromBase(defaults);
        KnockbackConfigResolver.KnockbackContext ctx = KnockbackConfigResolver.KnockbackContext.of(snap, services);
        KnockbackConfigResolver.ResolvedKnockbackConfig cfg = KnockbackConfigResolver.resolve(merged, ctx);

        DirContext dctx = dirCtx(snap);
        if (dctx == null) return null;
        int extraLevel = extraLevel(snap, cfg);
        boolean hasExtra = extraLevel > 0;

        Entity t = snap.target();
        Point oPt = dctx.oPt();
        Point tPt = t.getPosition();
        Vec dir3D = dctx.dir();

        Vec dDirH = Directions.horizontalBetween(oPt, tPt);
        Vec yDirH = Directions.horizontalOf(dir3D);
        Vec dDirV = Directions.verticalBetween(oPt, tPt);
        Vec pDirV = Directions.verticalOf(dir3D);

        RawDirs raw = new RawDirs(dDirH, dDirV, yDirH, pDirV);
        DirAndStrength normKb = resolveDS(raw, cfg, false);
        DirAndStrength extraKb = hasExtra ? resolveDS(raw, cfg, true) : null;

        Vec kb = normKb.direction().mul(normKb.h(), normKb.v(), normKb.h());
        // Vanilla scales the extra HORIZONTALLY by the level (i * 0.5F in EntityHuman.attack) while the
        // vertical stays fixed regardless of level - extraHorizontal is the per-level unit.
        Vec kbe = extraKb != null
                ? extraKb.direction().mul(extraKb.h() * extraLevel, extraKb.v(), extraKb.h() * extraLevel)
                : null;

        double iFH = frictionCoeff(or(cfg.frictionH(), 0), cfg.frictionModeH());
        double iFV = frictionCoeff(or(cfg.frictionV(), 0), cfg.frictionModeV());
        // Velocity tracking method, resolved ONCE here: KnockbackConfig override -> the victim's scoped profile
        // velocity (MechanicsProfile.velocity, where presets set it) -> VelocityRule.DEFAULT. The resolved rule is
        // threaded onto the component context below so a custom component reads the SAME velocity (ctx.victimVelocity()).
        VelocityRule velRule = cfg.velocity();
        if (velRule == null) velRule = services.profiles().velocityFor(t);
        if (velRule == null) velRule = VelocityRule.DEFAULT;
        Vec vel = velRule.estimate(VelocityContext.of(t, services.sprintTracker()));

        kb = new Vec(vel.x() * iFH + kb.x(), vel.y() * iFV + kb.y(), vel.z() * iFH + kb.z());

        if (cfg.horizontalBounds() != null) kb = applyHorizontalBounds(kb, cfg.horizontalBounds());
        if (cfg.verticalBounds() != null) kb = applyVerticalBounds(kb, cfg.verticalBounds());

        Vec kbVec = kbe != null ? addVectors(kb, kbe, cfg) : kb;

        if (kbe != null) {
            if (cfg.extraHorizontalBounds() != null) kbVec = applyHorizontalBounds(kbVec, cfg.extraHorizontalBounds());
            if (cfg.extraVerticalBounds() != null) kbVec = applyVerticalBounds(kbVec, cfg.extraVerticalBounds());
        }

        // Custom knockback components. They run on a context carrying the resolved velocity rule, so a component
        // (e.g. Minemen's axial drag / cap-hold) reads ctx.victimVelocity() - the same velocity folded above -
        // instead of pinning a rule onto the config.
        if (cfg.customComponents() != null) {
            KnockbackConfigResolver.KnockbackContext compCtx = ctx.withVelocity(velRule);
            for (KnockbackComponent comp : cfg.customComponents()) {
                Vec out = comp.apply(compCtx, kbVec);
                if (out != null) kbVec = out;
            }
        }

        return new Vec(kbVec.x() * tps, kbVec.y() * tps, kbVec.z() * tps);
    }

    /**
     * The extra-knockback level for this hit - vanilla {@code EntityHuman.attack}'s {@code i}: the Knockback
     * enchant level plus {@code 1} when the attacker is (recently) sprinting, applied only to melee hits. The
     * extra term's horizontal magnitude scales linearly with it. {@code 0} = no extra.
     */
    private int extraLevel(KnockbackSnapshot snap, KnockbackConfigResolver.ResolvedKnockbackConfig cfg) {
        if (!snap.melee()) return 0;
        Entity a = snap.source();
        if (a == null) return 0;
        int level = 0;
        // TODO(enchants): + the weapon's Knockback enchant level once item enchants/attributes exist.
        int sprBuf = cfg.sprintBuffer() != null ? cfg.sprintBuffer() : 0;
        if (SprintTracker.wasRecentlySprinting(services.sprintTracker(), a, sprBuf)) level++;
        return level;
    }

    /** Gets the direction context for this knockback. */
    private record DirContext(Point oPt, Vec dir) {}

    private @Nullable DirContext dirCtx(KnockbackSnapshot snap) {
        Entity t = snap.target();
        Point tPt = t.getPosition();
        Entity s = snap.source();
        Point oPt = snap.origin();
        Vec dir = snap.direction();

        if (s != null) return new DirContext(s.getPosition(), s.getPosition().direction());
        if (oPt != null && dir == null) return new DirContext(oPt, oPt.asPos().withLookAt(tPt).direction());
        if (dir != null) {
            Point pt = oPt != null ? oPt : tPt;
            return new DirContext(pt, pt.asPos().withDirection(dir).direction());
        } return null;
    }

    /** Direction + horizontal/vertical strengths */
    private record DirAndStrength(Vec direction, double h, double v) {}

    /** Raw position and yaw/pitch directions */
    private record RawDirs(Vec posH, Vec posV, Vec yaw, Vec pitch) {}

    private DirAndStrength resolveDS(RawDirs raw, KnockbackConfigResolver.ResolvedKnockbackConfig cfg, boolean extra) {
        double h = or(extra ? cfg.extraHorizontal() : cfg.horizontal(), 0);
        double v = or(extra ? cfg.extraVertical() : cfg.vertical(), 0);
        double yw = or(extra ? cfg.extraYawWeight() : cfg.yawWeight(), 0);
        double pw = or(extra ? cfg.extraPitchWeight() : cfg.pitchWeight(), 0);
        double hw = or(extra ? cfg.extraHeightDelta() : cfg.heightDelta(), 0);

        Vec dirH; Vec dirV; double magH = h; double magV = v;

        if (cfg.horizontalCombine() == KnockbackConfig.DirectionMode.VECTOR_ADDITION) {
            double posMag = h * (1 - yw);
            double lookMag = h * yw;
            double cx = raw.posH().x() * posMag + raw.yaw().x() * lookMag;
            double cz = raw.posH().z() * posMag + raw.yaw().z() * lookMag;
            double len = Math.sqrt(cx * cx + cz * cz);
            dirH = len < Directions.EPSILON ? raw.yaw() : new Vec(cx / len, 0, cz / len);
            magH = len < Directions.EPSILON ? h : len;
        } else {
            dirH = Directions.blend(raw.posH(), raw.yaw(), 1 - yw, yw, Directions::randomHorizontal);
        }

        if (cfg.verticalCombine() == KnockbackConfig.DirectionMode.VECTOR_ADDITION) {
            double heightMag = v * hw;
            double pitchMag = v * pw;
            double cy = raw.pitch().y() * pitchMag + raw.posV().y() * heightMag;
            double len = Math.abs(cy);
            dirV = len < Directions.EPSILON ? Directions.UP : new Vec(0, Math.signum(cy), 0);
            magV = len < Directions.EPSILON ? v : len;
        } else {
            dirV = Directions.blend(raw.pitch(), raw.posV(), pw, hw, () -> Directions.UP);
        }

        Vec dir3D = new Vec(dirH.x(), dirV.y(), dirH.z());

        return new DirAndStrength(dir3D, magH, magV);
    }

    private static Vec applyVerticalBounds(Vec v, KnockbackConfig.Bounds b) {
        double y = v.y();
        if (b.lower() != null) y = Math.max(y, b.lower());
        if (b.upper() != null) y = Math.min(y, b.upper());
        return new Vec(v.x(), y, v.z());
    }

    private static Vec applyHorizontalBounds(Vec v, KnockbackConfig.Bounds b) {
        double hMag = Math.sqrt(v.x() * v.x() + v.z() * v.z());
        if (hMag < Directions.EPSILON) return v;
        double mag = hMag;
        if (b.lower() != null && mag < b.lower()) mag = b.lower();
        if (b.upper() != null && mag > b.upper()) mag = b.upper();
        double scale = mag / hMag;
        return new Vec(v.x() * scale, v.y(), v.z() * scale);
    }

    private Vec addVectors(Vec a, Vec b, KnockbackConfigResolver.ResolvedKnockbackConfig cfg) {
        boolean hAdd = cfg.horizontalCombine() == KnockbackConfig.DirectionMode.VECTOR_ADDITION;
        boolean vAdd = cfg.verticalCombine() == KnockbackConfig.DirectionMode.VECTOR_ADDITION;

        double resX, resZ, resY;

        if (hAdd) {
            resX = a.x() + b.x();
            resZ = a.z() + b.z();
        } else {
            double magA = Math.sqrt(a.x() * a.x() + a.z() * a.z());
            double magB = Math.sqrt(b.x() * b.x() + b.z() * b.z());
            double hNet = magA + magB;

            if (hNet < Directions.EPSILON) {
                resX = resZ = 0;
            } else {
                double sumX = a.x() + b.x();
                double sumZ = a.z() + b.z();
                double len = Math.sqrt(sumX * sumX + sumZ * sumZ);
                if (len < Directions.EPSILON) {
                    resX = resZ = 0;
                } else {
                    double s = hNet / len;
                    resX = sumX * s;
                    resZ = sumZ * s;
                }
            }
        }

        if (vAdd) {
            resY = a.y() + b.y();
        } else {
            double vNet = Math.abs(a.y()) + Math.abs(b.y());
            double blendY = a.y() + b.y();
            resY = Math.max(-vNet, Math.min(vNet, blendY));
        }

        return new Vec(resX, resY, resZ);
    }

    private static double or(Double v, double def) { return v != null ? v : def; }

    /** Friction term coefficient: {@code FACTOR} multiplies mot by the value directly; {@code DIVISOR} (default, incl. null) uses {@code 1/value}. */
    private static double frictionCoeff(double f, @Nullable KnockbackConfig.FrictionMode mode) {
        if (mode == KnockbackConfig.FrictionMode.FACTOR) return f;
        return f > 0 ? 1.0 / f : 0;
    }
}
