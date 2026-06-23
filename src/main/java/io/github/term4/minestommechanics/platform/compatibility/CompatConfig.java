package io.github.term4.minestommechanics.platform.compatibility;

import net.minestom.server.entity.EntityPose;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Immutable cross-version compatibility config: per-scope knobs that make the server present consistent mechanics to
 * mixed-version clients (e.g. serving 1.8-style behavior to modern clients). Scoped via {@code MechanicsProfile.compat}
 * and pushed to {@code OptimizedPlayer} at spawn / on profile change by {@code PlayerConfigApplier}, like {@code PlayerConfig}.
 * Plain values - rarely-changing platform knobs, not per-hit values, so deliberately no {@code FieldValue}/subconfig
 * machinery. Unset ({@code null}) fields are left unmanaged.
 *
 * <p>{@code disabledPoses} - poses the server forces back to {@link EntityPose#STANDING} (e.g. {@code SWIMMING} for
 * swim/crawl, {@code FALL_FLYING} for elytra) so a modern client can't enter a pose 1.8 lacks. The pose visual itself is
 * client-authoritative (the client recomputes its own pose each tick), so this only fixes the server + other viewers; the
 * gameplay half is {@code restrictMovement}.
 *
 * <p>{@code restrictMovement} - rejects a move that would newly place the player's <em>server</em> hitbox in block
 * collision, so a client rendering itself crawling/swimming still cannot traverse a gap its server hitbox can't fit through
 * (Minemen behaviour). Uses the player's current bounding box, so with {@code legacyHitbox} on, the 1.5-block sneak gap is
 * restricted too. Enforced by {@code CompatMovement}.
 *
 * <p>{@code legacyHitbox} - keeps the server bounding box at standing dimensions regardless of pose (no modern crouch
 * shrink to 1.5) and uses 1.8 eye heights (1.54 sneaking vs the modern crouch eye), for server-side collision / drowning /
 * projectile spawn. The client still renders its own (shrunk) pose; this is the server-treated half. Enforced by
 * {@code OptimizedPlayer.getBoundingBox}/{@code getEyeHeight}.
 */
public final class CompatConfig {

    /** Poses forced back to {@code STANDING}; {@code null} = unmanaged, empty = none disabled. */
    public final @Nullable Set<EntityPose> disabledPoses;
    /** When {@code true}, a move placing the player's server hitbox in block collision is rejected (no crawl/sneak through a gap it can't fit). {@code null} = unmanaged. */
    public final @Nullable Boolean restrictMovement;
    /** When {@code true}, the server hitbox stays standing dimensions (no crouch shrink) and uses 1.8 eye heights. {@code null} = unmanaged. */
    public final @Nullable Boolean legacyHitbox;

    private CompatConfig(Builder b) {
        disabledPoses = b.disabledPoses;
        restrictMovement = b.restrictMovement;
        legacyHitbox = b.legacyHitbox;
    }

    public Builder toBuilder() { return new Builder(this); }

    public static Builder builder() { return builder(null); }
    public static Builder builder(@Nullable CompatConfig base) { return base != null ? new Builder(base) : new Builder(); }

    public static final class Builder {
        private @Nullable Set<EntityPose> disabledPoses;
        private @Nullable Boolean restrictMovement;
        private @Nullable Boolean legacyHitbox;

        Builder() {}

        Builder(CompatConfig c) {
            disabledPoses = c.disabledPoses;
            restrictMovement = c.restrictMovement;
            legacyHitbox = c.legacyHitbox;
        }

        /** Poses forced back to {@code STANDING} for in-scope players ({@code null} = unmanaged). Defensively copied. */
        public Builder disabledPoses(@Nullable Set<EntityPose> v) { disabledPoses = v != null ? Set.copyOf(v) : null; return this; }
        /** Convenience: disable the given poses (e.g. {@code SWIMMING}, {@code FALL_FLYING}). */
        public Builder disabledPoses(EntityPose... poses) { disabledPoses = Set.of(poses); return this; }
        /** Reject moves that place the player's server hitbox in block collision (no crawl/sneak through a too-small gap). */
        public Builder restrictMovement(@Nullable Boolean v) { restrictMovement = v; return this; }
        /** Keep the server hitbox at standing dimensions (no crouch shrink) + 1.8 eye heights. */
        public Builder legacyHitbox(@Nullable Boolean v) { legacyHitbox = v; return this; }

        public CompatConfig build() { return new CompatConfig(this); }
    }
}
