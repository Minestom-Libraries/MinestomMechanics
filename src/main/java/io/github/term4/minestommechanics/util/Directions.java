package io.github.term4.minestommechanics.util;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Direction math helpers (unit vectors, yaw convention, blends) shared by the mechanics. */
public final class Directions {

    private Directions() {}

    /** Up unit vector. */
    public static final Vec UP = new Vec(0, 1, 0);

    /** Length below which a direction is considered degenerate (falls back to a random/up direction). */
    public static final double EPSILON = 1e-6;

    /**
     * Minecraft yaw -> horizontal facing unit vector, {@code (x, z) = (-sin(yaw), cos(yaw))}.
     * Convention: yaw 0 -> +Z, 90 -> -X, -90 -> +X, 180 -> -Z. The y component is always 0.
     */
    public static Vec fromYaw(double yawDeg) {
        double rad = Math.toRadians(yawDeg);
        return new Vec(-Math.sin(rad), 0, Math.cos(rad));
    }

    // TODO: Could make this 3D later?
    /**
     * Snaps a horizontal vector onto whichever cardinal axis carries the larger magnitude, returning a unit vector
     * with components in {@code {-1, 0, +1}}: e.g. {@code (0.9, 0.1) -> (+1, 0)}, {@code (0.1, -0.9) -> (0, -1)}.
     * Ties favour x; a zero vector snaps to {@code (0, 0)}.
     */
    public static Vec snapDominantAxis(Vec v) {
        return Math.abs(v.x()) >= Math.abs(v.z())
                ? new Vec(Math.signum(v.x()), 0, 0)
                : new Vec(0, 0, Math.signum(v.z()));
    }

    /** Horizontal (xz) unit direction from {@code from} to {@code to}; {@link #randomHorizontal()} when degenerate. */
    public static Vec horizontalBetween(Point from, Point to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < EPSILON) return randomHorizontal();
        return new Vec(dx / dist, 0, dz / dist);
    }

    /** Vertical unit direction (+-y) from the height delta {@code from -> to}; {@link #UP} when level. */
    public static Vec verticalBetween(Point from, Point to) {
        double dy = to.y() - from.y();
        if (Math.abs(dy) < EPSILON) return UP;
        return new Vec(0, Math.signum(dy), 0);
    }

    /** Horizontal (xz) unit direction of a 3D unit vector; {@link #randomHorizontal()} when looking straight up/down. */
    public static Vec horizontalOf(Vec dir) {
        double len = Math.sqrt(dir.x() * dir.x() + dir.z() * dir.z());
        if (len < EPSILON) return randomHorizontal();
        return new Vec(dir.x() / len, 0, dir.z() / len);
    }

    /** Vertical unit direction (+-y) of a 3D unit vector; {@link #UP} when horizontal. */
    public static Vec verticalOf(Vec dir) {
        if (Math.abs(dir.y()) < EPSILON) return UP;
        return new Vec(0, Math.signum(dir.y()), 0);
    }

    /** Random horizontal (xz) unit vector. */
    public static Vec randomHorizontal() {
        Vec v;
        do {
            double x = ThreadLocalRandom.current().nextDouble() * 2 - 1;
            double z = ThreadLocalRandom.current().nextDouble() * 2 - 1;
            v = new Vec(x, 0, z);
        } while (v.length() < EPSILON);
        return v.normalize();
    }

    /** Weighted blend of two vectors, normalized; {@code fallback} when the weights or the sum are degenerate. */
    public static Vec blend(Vec a, Vec b, double wA, double wB, Supplier<Vec> fallback) {
        if (wA <= 0 && wB <= 0) return fallback.get();
        Vec sum = a.mul(wA).add(b.mul(wB));
        return sum.lengthSquared() < EPSILON * EPSILON ? fallback.get() : sum.normalize();
    }
}
