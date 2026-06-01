package io.github.term4.minestommechanics.mechanics.knockback;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.mechanics.Cause;
import io.github.term4.minestommechanics.util.SprintTracker;
import io.github.term4.minestommechanics.util.VelocityEstimator;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class KnockbackCalculator {

    private final Services services;
    private final KnockbackConfig defaults;

    private static final double MIN_DIST = 1e-6; // distance at which position delta direction is degenerate
    private final double tps = ServerFlag.SERVER_TICKS_PER_SECOND;

    public KnockbackCalculator(Services services, KnockbackConfig defaults) {
        this.services = services;
        this.defaults = defaults;
    }

    public @Nullable Vec compute(KnockbackSnapshot snap) {
        KnockbackConfig base = snap.config();
        if (base == null) return null;

        KnockbackConfig merged = base.fromBase(defaults);
        KnockbackConfigResolver.KnockbackContext ctx = KnockbackConfigResolver.KnockbackContext.of(snap, services);
        KnockbackConfigResolver.ResolvedKnockbackConfig cfg = KnockbackConfigResolver.resolve(merged, ctx);

        DirContext dctx = dirCtx(snap);
        if (dctx == null) return null;
        Cause cause = snap.cause();
        boolean hasExtra = extra(snap, cfg) > 0;

        Entity t = snap.target();
        Point oPt = dctx.oPt();
        Point tPt = t.getPosition();
        Vec dir3D = dctx.dir();

        Vec dDirH = deltaH(oPt, tPt);
        Vec yDirH = yawDir(dir3D);
        Vec dDirV = deltaV(oPt, tPt);
        Vec pDirV = pitchDir(dir3D);

        RawDirs raw = new RawDirs(dDirH, dDirV, yDirH, pDirV);
        DirAndStrength normKb = resolveDS(raw, cfg, false);
        DirAndStrength extraKb = hasExtra ? resolveDS(raw, cfg, true) : null;

        Vec kb = normKb.direction().mul(normKb.h(), normKb.v(), normKb.h());
        Vec kbe = extraKb != null ? extraKb.direction().mul(extraKb.h(), extraKb.v(), extraKb.h()) : null;

        kb = applyRr(kb, oPt, tPt, cfg, false);
        kb = applySweeping(kb, cause, cfg, false);

        if (kbe != null) {
            kbe = applyRr(kbe, oPt, tPt, cfg, true);
            kbe = applySweeping(kbe, cause, cfg, true);
        }

        Double fHVal = kbe != null ? cfg.frictionExtraH() : cfg.frictionH();
        Double fVVal = kbe != null ? cfg.frictionExtraV() : cfg.frictionV();
        double fH = fHVal != null ? fHVal : 0;
        double fV = fVVal != null ? fVVal : 0;
        double iFH = fH > 0 ? 1.0 / fH : 0;
        double iFV = fV > 0 ? 1.0 / fV : 0;
        boolean useAbsH = Boolean.TRUE.equals(kbe != null ? cfg.useAbsFrictionEH() : cfg.useAbsFrictionH());
        boolean useAbsV = Boolean.TRUE.equals(kbe != null ? cfg.useAbsFrictionEV() : cfg.useAbsFrictionV());
        boolean extra = kbe != null;
        KnockbackConfig.VelocityMethod modeH = extra ? cfg.velocityModeExtraH() : cfg.velocityModeH();
        KnockbackConfig.VelocityMethod modeV = extra ? cfg.velocityModeExtraV() : cfg.velocityModeV();
        KnockbackConfig.VelocityMethod fallback = cfg.velocityMethod() != null ? cfg.velocityMethod() : KnockbackConfig.VelocityMethod.DELTA;
        if (modeH == null) modeH = fallback;
        if (modeV == null) modeV = fallback;
        Vec velH = VelocityEstimator.getVelocityForMethod(t, modeH, cfg.gravityPredictPerTick(), cfg.gravityPredictScale());
        Vec velV = VelocityEstimator.getVelocityForMethod(t, modeV, cfg.gravityPredictPerTick(), cfg.gravityPredictScale());
        double motX = useAbsH ? -Math.abs(velH.x()) : velH.x();
        double motY = useAbsV ? -Math.abs(velV.y()) : velV.y();
        double motZ = useAbsH ? -Math.abs(velH.z()) : velH.z();
        kb = new Vec(motX * iFH + kb.x(), motY * iFV + kb.y(), motZ * iFH + kb.z());

        if (cfg.horizontalBounds() != null) kb = applyHorizontalBounds(kb, cfg.horizontalBounds());
        if (cfg.verticalBounds() != null) kb = applyVerticalBounds(kb, cfg.verticalBounds());

        Vec kbVec = kbe != null ? addVectors(kb, kbe, cfg) : kb;

        if (kbe != null) {
            if (cfg.extraHorizontalBounds() != null) kbVec = applyHorizontalBounds(kbVec, cfg.extraHorizontalBounds());
            if (cfg.extraVerticalBounds() != null) kbVec = applyVerticalBounds(kbVec, cfg.extraVerticalBounds());
        }

        return new Vec(kbVec.x() * tps, kbVec.y() * tps, kbVec.z() * tps);
    }

    public KnockbackConfigResolver.ResolvedKnockbackConfig resolveConfig(KnockbackSnapshot snap) {
        KnockbackConfig merged = snap.config() != null ? snap.config().fromBase(defaults) : defaults;
        KnockbackConfigResolver.KnockbackContext ctx = KnockbackConfigResolver.KnockbackContext.of(snap, services);
        return KnockbackConfigResolver.resolve(merged, ctx);
    }

    private int extra(KnockbackSnapshot snap, KnockbackConfigResolver.ResolvedKnockbackConfig cfg) {
        if (!snap.cause().isMelee()) return 0;
        Entity a = snap.source();
        if (a == null) return 0;
        int sprBuf = cfg.sprintBuffer() != null ? cfg.sprintBuffer() : 0;
        return SprintTracker.wasRecentlySprinting(services.sprintTracker(), a, sprBuf) ? 1 : 0;
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
            dirH = len < MIN_DIST ? raw.yaw() : new Vec(cx / len, 0, cz / len);
            magH = len < MIN_DIST ? h : len;
        } else {
            dirH = blend(raw.posH(), raw.yaw(), 1 - yw, yw, KnockbackCalculator::ranDirH);
        }

        if (cfg.verticalCombine() == KnockbackConfig.DirectionMode.VECTOR_ADDITION) {
            double heightMag = v * hw;
            double pitchMag = v * pw;
            double cy = raw.pitch().y() * pitchMag + raw.posV().y() * heightMag;
            double len = Math.abs(cy);
            dirV = len < MIN_DIST ? UP : new Vec(0, Math.signum(cy), 0);
            magV = len < MIN_DIST ? v : len;
        } else {
            dirV = blend(raw.pitch(), raw.posV(), pw, hw, () -> UP);
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
        if (hMag < MIN_DIST) return v;
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

            if (hNet < MIN_DIST) {
                resX = resZ = 0;
            } else {
                double sumX = a.x() + b.x();
                double sumZ = a.z() + b.z();
                double len = Math.sqrt(sumX * sumX + sumZ * sumZ);
                if (len < MIN_DIST) {
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

    private static final Vec UP = new Vec(0, 1, 0);

    /** Horizontal (xz) direction from attacker to victim. Null if degenerate. */
    private static Vec deltaH(Point sPt, Point tPt) {
        double dx = tPt.x() - sPt.x();
        double dz = tPt.z() - sPt.z();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < MIN_DIST) return ranDirH();
        return new Vec(dx / dist, 0, dz / dist);
    }

    /** Vertical unit direction from height delta (is the victim above or below the attacker. */
    private static Vec deltaV(Point sPt, Point tPt) {
        double dy = tPt.y() - sPt.y();
        if (Math.abs(dy) < MIN_DIST) return UP; // defaults to up
        return new Vec(0, Math.signum(dy), 0);
    }

    /** Vertical unit direction from 3D unit vector (determines up / down). */
    private static Vec pitchDir(Vec dir) {
        if (Math.abs(dir.y()) < MIN_DIST) return UP;
        return new Vec(0, Math.signum(dir.y()), 0);
    }

    /** Horizontal direction from 3D unit vector. */
    private static Vec yawDir(Vec dir) {
        double len = Math.sqrt(dir.x() * dir.x() + dir.z() * dir.z());
        if (len < MIN_DIST) return ranDirH();
        return new Vec(dir.x() / len, 0, dir.z() / len);
    }

    /** Returns a random horizontal vector. */
    private static Vec ranDirH() {
        Vec v;
        do {
            double x = ThreadLocalRandom.current().nextDouble() * 2 - 1;
            double z = ThreadLocalRandom.current().nextDouble() * 2 - 1;
            v = new Vec(x, 0, z);
        } while (v.length() < MIN_DIST);
        return v.normalize();
    }

    /** Weighted blend of two vectors. Uses fallback when sum is degenerate. */
    private static Vec blend(Vec a, Vec b, double wA, double wB, Supplier<Vec> ranDir) {
        if (wA <= 0 && wB <= 0) return ranDir.get();
        Vec sum = a.mul(wA).add(b.mul(wB));
        return sum.lengthSquared() < MIN_DIST *  MIN_DIST ? ranDir.get() : sum.normalize();
    }

    private Vec applyRr(Vec kb, Point aPt, Point vPt, KnockbackConfigResolver.ResolvedKnockbackConfig cfg, boolean hasExtra) {
        double dh = Math.sqrt(Math.pow(vPt.x() - aPt.x(), 2) + Math.pow(vPt.z() - aPt.z(), 2));
        double dv = Math.abs(vPt.y() - aPt.y());

        double rsh = or(hasExtra ? cfg.rangeStartExtraH() : cfg.rangeStartH(), 0);
        double rfh = or(hasExtra ? cfg.rangeFactorExtraH() : cfg.rangeFactorH(), 0);
        double rsv = or(hasExtra ? cfg.rangeStartExtraV() : cfg.rangeStartV(), 0);
        double rfv = or(hasExtra ? cfg.rangeFactorExtraV() : cfg.rangeFactorV(), 0);

        double sh = dh <= rsh ? 1.0 : 1.0 - rfh * (dh - rsh);
        double sv = dv <= rsv ? 1.0 : 1.0 - rfv * (dv - rsv);

        Double rmh = cfg.rangeMaxH();
        Double rmv = cfg.rangeMaxV();
        if (rmh != null && rmh > 0) sh = Math.max(sh, rmh);
        if (rmv != null && rmv > 0) sv = Math.max(sv, rmv);

        sh = Math.max(0, Math.min(1, sh));
        sv = Math.max(0, Math.min(1, sv));

        return new Vec(kb.x() * sh, kb.y() * sv, kb.z() * sh);
    }

    private Vec applySweeping(Vec kb, Cause cause, KnockbackConfigResolver.ResolvedKnockbackConfig cfg, boolean hasExtra) {
        if (cause != Cause.SWEEPING) return kb;
        double sfh = or(hasExtra ? cfg.sweepFactorExtraH() : cfg.sweepFactorH(), 0);
        double sfv = or(hasExtra ? cfg.sweepFactorExtraV() : cfg.sweepFactorV(), 0);
        double sh = 1.0 - sfh;
        double sv = 1.0 - sfv;
        return new Vec(kb.x() * sh, kb.y() * sv, kb.z() * sh);
    }

    private static double or(Double v, double def) { return v != null ? v : def; }
}
