package io.github.term4.minestommechanics.mechanics.projectile.entities;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.ShapeImpl;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.utils.block.BlockIterator;
import net.minestom.server.utils.chunk.ChunkCache;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 1.8-faithful block raytrace for {@link io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig.BlockCollisionMode#RAYTRACE}:
 * replicates vanilla 1.8's {@code World.rayTrace(pos, pos + motion, ...)} - the per-tick ray the 1.8 client runs in
 * {@code EntityArrow.t_} / {@code EntityProjectile.t_} - so a 1.8 client (through Via) agrees with the server about
 * block hits at block EDGES, where Minestom's swept-box physics and the client's ray disagree (the old "F8" brief
 * false-stick). The server is position-authoritative, so the two can't be reconciled by syncing; matching the client's
 * detection method is the only way to agree.
 *
 * <p>Walks the voxels the segment {@code start -> start + motion} passes through with the native
 * {@link BlockIterator}, and for each block ray-clips its collision AABBs - pulled from the block's real collision
 * {@link ShapeImpl#boundingBoxes() shape} so slabs / stairs / fences use their actual boxes, like 1.8's
 * {@code block.a(world, pos)} AABB - keeping the CLOSEST entry across the path (the first block the ray enters, which
 * is exactly what 1.8's first-hit DDA returns). The hit feeds {@link ProjectileEntity#stick} (block / point / axis).
 *
 * <p>Not bit-identical to 1.8 (the ray-AABB clip is a standard slab test and the block shapes are Minestom's modern
 * shapes, not 1.8's), but it detects against the same geometry with the same point-ray, so it agrees with the 1.8
 * client far better than the swept box at edges - which is the whole point of the knob.
 */
final class BlockRaytrace {

    private BlockRaytrace() {}

    /** Closest block the ray entered this tick: the struck {@code block}, the exact entry {@code point}, and the
     *  entry {@code axis} ({@code 0=X, 1=Y, 2=Z}) for {@link ProjectileEntity#stick}'s stuck-face normal. */
    record Hit(Block block, Point point, int axis) {}

    /** Safety cap on voxel steps (a fast projectile spans only a few blocks/tick; mirrors 1.8's 200-step guard). */
    private static final int MAX_STEPS = 256;

    /**
     * Ray-traces {@code start -> start + motion} against block collision shapes; returns the closest block entered this
     * tick ({@code t in [0, 1]}), or {@code null} if the path is clear. Only blocks WITH a collision shape are tested
     * (air / plants / fluids have an empty shape), matching 1.8's "block has a bounding box" gate.
     */
    static @Nullable Hit cast(Instance instance, @Nullable Chunk startChunk, Pos start, Vec motion) {
        if (motion.isZero()) return null;
        final Chunk chunk = startChunk != null ? startChunk : instance.getChunkAt(start.x(), start.z());
        final ChunkCache getter = new ChunkCache(instance, chunk, Block.AIR);

        double bestT = Double.MAX_VALUE;
        Block bestBlock = null;
        int bestAxis = -1;

        final BlockIterator it = new BlockIterator(start.asVec(), motion, 0, motion.length(), false);
        int steps = 0;
        while (it.hasNext() && steps++ < MAX_STEPS) {
            final Point bp = it.next();
            final Block block = getter.getBlock(bp.blockX(), bp.blockY(), bp.blockZ(), Block.Getter.Condition.TYPE);
            if (!(block.registry().collisionShape() instanceof ShapeImpl shape)) continue;
            final List<BoundingBox> boxes = shape.boundingBoxes();
            if (boxes.isEmpty()) continue; // non-collidable (air, plants, fluids) - no block bounding box
            for (BoundingBox box : boxes) {
                final AxisHit hit = clip(start, motion, box, bp.blockX(), bp.blockY(), bp.blockZ(), bestT);
                if (hit != null) { // closer than every box seen so far (clip enforces hit.t < bestT)
                    bestT = hit.t();
                    bestAxis = hit.axis();
                    bestBlock = block;
                }
            }
        }

        if (bestBlock == null) return null;
        return new Hit(bestBlock, start.add(motion.mul(bestT)), bestAxis);
    }

    /** Entry {@code t} ({@code [0,1]} along the tick's motion) + entry {@code axis} ({@code 0=X,1=Y,2=Z}) of a clip. */
    private record AxisHit(double t, int axis) {}

    /**
     * Slab ray-AABB clip of {@code start + t*motion} ({@code t in [0,1]}) against {@code box} offset to block
     * {@code (bx,by,bz)}. Returns the entry {@code t} + entry axis when the ray ENTERS the box from OUTSIDE within this
     * tick and closer than {@code maxT}; {@code null} otherwise (miss / behind / parallel-outside / started-inside /
     * farther than maxT). Allocates only on a hit (the rare path - a hit ends the projectile's flight).
     */
    private static @Nullable AxisHit clip(Pos start, Vec motion, BoundingBox box, int bx, int by, int bz, double maxT) {
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
        int axis = -1;
        for (int i = 0; i < 3; i++) {
            final double p = comp(start, i), d = comp(motion, i);
            final double lo = boxMin(box, i) + blockCoord(bx, by, bz, i);
            final double hi = boxMax(box, i) + blockCoord(bx, by, bz, i);
            if (Math.abs(d) < Vec.EPSILON) {
                if (p < lo || p > hi) return null; // parallel to this slab and outside it
            } else {
                double t1 = (lo - p) / d, t2 = (hi - p) / d;
                if (t1 > t2) { final double tmp = t1; t1 = t2; t2 = tmp; }
                if (t1 > tmin) { tmin = t1; axis = i; }
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) return null; // slabs don't overlap -> ray misses the box
            }
        }
        // Hit only if the ray ENTERS from outside (tmin >= 0) within this tick (tmin <= 1) and is the closest so far.
        if (axis == -1 || tmin < 0 || tmin > 1 || tmin >= maxT) return null;
        return new AxisHit(tmin, axis);
    }

    private static double comp(Point v, int axis) { return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z(); }
    private static double boxMin(BoundingBox b, int axis) { return axis == 0 ? b.minX() : axis == 1 ? b.minY() : b.minZ(); }
    private static double boxMax(BoundingBox b, int axis) { return axis == 0 ? b.maxX() : axis == 1 ? b.maxY() : b.maxZ(); }
    private static double blockCoord(int bx, int by, int bz, int axis) { return axis == 0 ? bx : axis == 1 ? by : bz; }
}
