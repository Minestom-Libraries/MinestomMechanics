package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ProjectileContext;
import net.kyori.adventure.key.Key;
import net.minestom.server.collision.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Per-type projectile configuration: everything tunable about one projectile (snowball, arrow, ...), keyed by
 * {@link #key()}. Mirrors {@code DamageTypeConfig} - every value is a {@link FieldValue} resolved against a
 * {@link ProjectileContext}, so any knob can be a constant or a per-launch lambda (e.g. read {@code ctx.item()}
 * for a custom item). Unset fields fall back through the resolver chain - per-type override -&gt; the active
 * {@link ProjectileConfig}'s generic {@link ProjectileConfig#defaults() defaults} -&gt; the type's intrinsic
 * {@link ProjectileType#defaultConfig()} -&gt; hard fallbacks - so a sparse override only restates what it changes,
 * and presets keep the shared baseline in one generic config (e.g. {@code Vanilla18.projectileDefaults()}).
 *
 * <p>Configurable parity with the old system: bounding box, spawn offset (forward + vertical from eye), initial
 * speed/power curve, spread, shooter-momentum inheritance, aerodynamics (gravity + horizontal/vertical drag),
 * shooter-immunity ticks, position-sync interval, the hit {@link KnockbackConfig} and {@link DamageType}/amount,
 * and the remove-on-hit policies. Launchers resolve this once per launch and stamp the entity - entities stay dumb.
 */
public final class ProjectileTypeConfig {

    /**
     * Where a hit's knockback originates. The two frames are orthogonal to the {@link KnockbackConfig}'s direction
     * blending ({@code yawWeight}): the source picks the origin + the look basis, the config shapes the final vector.
     * <ul>
     *   <li>{@link #PROJECTILE} - origin = the projectile, look basis = its flight path (vanilla default).</li>
     *   <li>{@link #SHOOTER} - origin = the shooter, look basis = the shooter's facing. Carries the shooter as the
     *       snapshot {@code source} (like melee), so {@code yawWeight = 0} pushes shooter -&gt; victim and
     *       {@code yawWeight = 1} pushes along the shooter's yaw - e.g. a self-hit KB along the look is just a
     *       {@code KnockbackConfig} with {@code yawWeight(1)}, no special source.</li>
     * </ul>
     * Full control is available by cancelling {@code ProjectileHitEvent} and applying your own snapshot.
     */
    public enum KnockbackSource { PROJECTILE, SHOOTER }

    /**
     * What a projectile does for a hit it does not deal normally - used by BOTH {@link #selfHit} (it hit its own
     * shooter) and {@link #invulnHit} (the hit was blocked because the target was invulnerable / creative):
     * <ul>
     *   <li>{@link #HIT} - the normal hit (damage / knockback / impact / break per {@code removeOnEntityHit}). For
     *       {@code selfHit} = "you may hit yourself"; for {@code invulnHit} it is N/A (the hit already did not land)
     *       and behaves like {@link #DESTROY}.</li>
     *   <li>{@link #PASS_THROUGH} - ignore the entity; the projectile keeps flying unchanged (the 1.8 ender pearl
     *       through its thrower; a Hypixel "self does nothing").</li>
     *   <li>{@link #DEFLECT} - bounce off (vanilla arrow {@code motion *= -0.1}); no damage/KB/break, keeps flying.</li>
     *   <li>{@link #DESTROY} - break the projectile (its impact effect fires, then it is removed); no damage/KB. The
     *       vanilla throwable response to an invuln hit (snowball/egg/pearl {@code die()} regardless).</li>
     * </ul>
     * Note: "bypass" (deal the hit anyway despite invul) is NOT here - that is the damage type's {@code bypassInvul}
     * (a damage-config concern); if a type bypasses, the hit lands and never reaches {@code invulnHit}.
     */
    public enum HitResponse { HIT, PASS_THROUGH, DEFLECT, DESTROY }

    /**
     * How the projectile detects BLOCK hits in flight - a flight knob, stamped on the entity at launch and read per
     * tick (orthogonal to the shared entity-hit + stick machinery):
     * <ul>
     *   <li>{@link #SWEPT} (default) - Minestom's swept-box physics ({@code CollisionUtils.handlePhysics}). The modern
     *       (1.21+) client's own collision matches it, so the modern preset keeps this.</li>
     *   <li>{@link #RAYTRACE} - a 1.8-faithful voxel raytrace of {@code position -> position + velocity} against block
     *       collision shapes, replicating vanilla 1.8's {@code World.rayTrace} (the ray the 1.8 client runs each tick in
     *       {@code EntityArrow.t_} / {@code EntityProjectile.t_}). A 1.8-PvP server (clients through Via) sets this so
     *       block hits agree with the client 1:1 at block EDGES, where the swept box and the client's ray disagree
     *       (the old "F8" brief false-stick). Implemented by {@code BlockRaytrace}.</li>
     * </ul>
     */
    public enum BlockCollisionMode { SWEPT, RAYTRACE }

    /**
     * Pickup geometry for a collectable projectile (arrows). A projectile is collected when its {@code boxWidth x
     * boxHeight} box intersects a player's bounding box inflated by {@code (inflateH, inflateV, inflateH)} - the exact
     * vanilla test ({@code Player#aiStep} / 1.8 {@code EntityHuman}: {@code grow(1, 0.5, 1)}; arrow box
     * {@code setSize(0.5, 0.5)}). Defaults are vanilla; only {@code ArrowEntity} reads it.
     */
    public record PickupBox(double inflateH, double inflateV, double boxWidth, double boxHeight) {
        /** Vanilla 1.8 / 26.1 pickup geometry: player box inflated (1, 0.5, 1), arrow box 0.5 x 0.5. */
        public static final PickupBox VANILLA = new PickupBox(1.0, 0.5, 0.5, 0.5);
    }

    private final Key key;
    private final @Nullable FieldValue<ProjectileContext, Boolean> enabled;
    private final @Nullable FieldValue<ProjectileContext, BoundingBox> boundingBox;
    private final @Nullable FieldValue<ProjectileContext, Double> gravity;
    private final @Nullable FieldValue<ProjectileContext, Double> horizontalDrag;
    private final @Nullable FieldValue<ProjectileContext, Double> verticalDrag;
    private final @Nullable FieldValue<ProjectileContext, Double> spawnOffsetH;
    private final @Nullable FieldValue<ProjectileContext, Double> spawnOffsetV;
    /** Sideways spawn offset perpendicular to the look (vanilla 1.8 throwing-hand shift, {@code 0.16}; 26.1: 0). */
    private final @Nullable FieldValue<ProjectileContext, Double> spawnOffsetLateral;
    private final @Nullable FieldValue<ProjectileContext, Double> speed;
    private final @Nullable FieldValue<ProjectileContext, Double> spread;
    private final @Nullable FieldValue<ProjectileContext, Boolean> inheritMomentum;
    private final @Nullable FieldValue<ProjectileContext, Integer> shooterImmunityTicks;
    /** Entity-hit margin: the target's bbox grows by this on EACH side for the hit ray-test (vanilla 1.8 {@code 0.3}
     *  for arrows + throwables); a flight knob, stamped on the entity and read per-tick. */
    private final @Nullable FieldValue<ProjectileContext, Double> entityHitGrow;
    /** How block hits are detected ({@link BlockCollisionMode}; default {@code SWEPT}). Vanilla 1.8 = {@code RAYTRACE}
     *  for 1:1 block-edge agreement with a 1.8 client; a flight knob, stamped on the entity and read per-tick. */
    private final @Nullable FieldValue<ProjectileContext, BlockCollisionMode> blockCollision;
    /** What the projectile does when it hits its own shooter ({@link HitResponse}; default {@code HIT} = vanilla). */
    private final @Nullable FieldValue<ProjectileContext, HitResponse> selfHit;
    private final @Nullable FieldValue<ProjectileContext, Integer> syncInterval;
    private final @Nullable FieldValue<ProjectileContext, KnockbackConfig> knockback;
    private final @Nullable FieldValue<ProjectileContext, KnockbackSource> knockbackSource;
    private final @Nullable FieldValue<ProjectileContext, Double> damage;
    private final @Nullable FieldValue<ProjectileContext, DamageType> damageType;
    private final @Nullable FieldValue<ProjectileContext, Boolean> removeOnEntityHit;
    private final @Nullable FieldValue<ProjectileContext, Boolean> removeOnBlockHit;
    /** What the projectile does when its hit is blocked (target invulnerable/creative): {@link HitResponse} -
     *  vanilla arrows {@code DEFLECT}, throwables {@code DESTROY} (default). */
    private final @Nullable FieldValue<ProjectileContext, HitResponse> invulnHit;
    /** Pickup geometry (collectable projectiles only, e.g. arrows); default {@link PickupBox#VANILLA}. */
    private final @Nullable FieldValue<ProjectileContext, PickupBox> pickupBox;
    private final @Nullable Function<ProjectileContext, ProjectileTypeConfig> subConfig;

    ProjectileTypeConfig(Builder b) {
        this.key = b.key;
        this.enabled = b.enabled;
        this.boundingBox = b.boundingBox;
        this.gravity = b.gravity;
        this.horizontalDrag = b.horizontalDrag;
        this.verticalDrag = b.verticalDrag;
        this.spawnOffsetH = b.spawnOffsetH;
        this.spawnOffsetV = b.spawnOffsetV;
        this.spawnOffsetLateral = b.spawnOffsetLateral;
        this.speed = b.speed;
        this.spread = b.spread;
        this.inheritMomentum = b.inheritMomentum;
        this.shooterImmunityTicks = b.shooterImmunityTicks;
        this.entityHitGrow = b.entityHitGrow;
        this.blockCollision = b.blockCollision;
        this.selfHit = b.selfHit;
        this.syncInterval = b.syncInterval;
        this.knockback = b.knockback;
        this.knockbackSource = b.knockbackSource;
        this.damage = b.damage;
        this.damageType = b.damageType;
        this.removeOnEntityHit = b.removeOnEntityHit;
        this.removeOnBlockHit = b.removeOnBlockHit;
        this.invulnHit = b.invulnHit;
        this.pickupBox = b.pickupBox;
        this.subConfig = b.subConfig;
    }

    public Key key() { return key; }

    public boolean enabled(ProjectileContext ctx) { Boolean v = resolve(enabled, ctx); return v == null || v; }
    public @Nullable BoundingBox boundingBox(ProjectileContext ctx) { return resolve(boundingBox, ctx); }
    public @Nullable Double gravity(ProjectileContext ctx) { return resolve(gravity, ctx); }
    public @Nullable Double horizontalDrag(ProjectileContext ctx) { return resolve(horizontalDrag, ctx); }
    public @Nullable Double verticalDrag(ProjectileContext ctx) { return resolve(verticalDrag, ctx); }
    public @Nullable Double spawnOffsetH(ProjectileContext ctx) { return resolve(spawnOffsetH, ctx); }
    public @Nullable Double spawnOffsetV(ProjectileContext ctx) { return resolve(spawnOffsetV, ctx); }
    public @Nullable Double spawnOffsetLateral(ProjectileContext ctx) { return resolve(spawnOffsetLateral, ctx); }
    public @Nullable Double speed(ProjectileContext ctx) { return resolve(speed, ctx); }
    public @Nullable Double spread(ProjectileContext ctx) { return resolve(spread, ctx); }
    public @Nullable Boolean inheritMomentum(ProjectileContext ctx) { return resolve(inheritMomentum, ctx); }
    public @Nullable Integer shooterImmunityTicks(ProjectileContext ctx) { return resolve(shooterImmunityTicks, ctx); }
    public @Nullable Double entityHitGrow(ProjectileContext ctx) { return resolve(entityHitGrow, ctx); }
    public @Nullable BlockCollisionMode blockCollision(ProjectileContext ctx) { return resolve(blockCollision, ctx); }
    public @Nullable HitResponse selfHit(ProjectileContext ctx) { return resolve(selfHit, ctx); }
    public @Nullable Integer syncInterval(ProjectileContext ctx) { return resolve(syncInterval, ctx); }
    public @Nullable KnockbackConfig knockback(ProjectileContext ctx) { return resolve(knockback, ctx); }
    public @Nullable KnockbackSource knockbackSource(ProjectileContext ctx) { return resolve(knockbackSource, ctx); }
    public @Nullable Double damage(ProjectileContext ctx) { return resolve(damage, ctx); }
    public @Nullable DamageType damageType(ProjectileContext ctx) { return resolve(damageType, ctx); }
    public @Nullable Boolean removeOnEntityHit(ProjectileContext ctx) { return resolve(removeOnEntityHit, ctx); }
    public @Nullable Boolean removeOnBlockHit(ProjectileContext ctx) { return resolve(removeOnBlockHit, ctx); }
    public @Nullable HitResponse invulnHit(ProjectileContext ctx) { return resolve(invulnHit, ctx); }
    public @Nullable PickupBox pickupBox(ProjectileContext ctx) { return resolve(pickupBox, ctx); }
    public @Nullable Function<ProjectileContext, ProjectileTypeConfig> subConfig() { return subConfig; }

    /** Merges this config over {@code base}: this config's set fields win, unset fields fall back per resolution. */
    public ProjectileTypeConfig fromBase(ProjectileTypeConfig base) {
        Builder b = new Builder(key != null ? key : base.key);
        b.enabled = mergeFv(enabled, base.enabled);
        b.boundingBox = mergeFv(boundingBox, base.boundingBox);
        b.gravity = mergeFv(gravity, base.gravity);
        b.horizontalDrag = mergeFv(horizontalDrag, base.horizontalDrag);
        b.verticalDrag = mergeFv(verticalDrag, base.verticalDrag);
        b.spawnOffsetH = mergeFv(spawnOffsetH, base.spawnOffsetH);
        b.spawnOffsetV = mergeFv(spawnOffsetV, base.spawnOffsetV);
        b.spawnOffsetLateral = mergeFv(spawnOffsetLateral, base.spawnOffsetLateral);
        b.speed = mergeFv(speed, base.speed);
        b.spread = mergeFv(spread, base.spread);
        b.inheritMomentum = mergeFv(inheritMomentum, base.inheritMomentum);
        b.shooterImmunityTicks = mergeFv(shooterImmunityTicks, base.shooterImmunityTicks);
        b.entityHitGrow = mergeFv(entityHitGrow, base.entityHitGrow);
        b.blockCollision = mergeFv(blockCollision, base.blockCollision);
        b.selfHit = mergeFv(selfHit, base.selfHit);
        b.syncInterval = mergeFv(syncInterval, base.syncInterval);
        b.knockback = mergeFv(knockback, base.knockback);
        b.knockbackSource = mergeFv(knockbackSource, base.knockbackSource);
        b.damage = mergeFv(damage, base.damage);
        b.damageType = mergeFv(damageType, base.damageType);
        b.removeOnEntityHit = mergeFv(removeOnEntityHit, base.removeOnEntityHit);
        b.removeOnBlockHit = mergeFv(removeOnBlockHit, base.removeOnBlockHit);
        b.invulnHit = mergeFv(invulnHit, base.invulnHit);
        b.pickupBox = mergeFv(pickupBox, base.pickupBox);
        b.subConfig = subConfig != null ? subConfig : base.subConfig;
        return b.build();
    }

    private static <T> @Nullable T resolve(@Nullable FieldValue<ProjectileContext, T> fv, ProjectileContext ctx) {
        return fv != null ? fv.resolve(ctx) : null;
    }

    private static <T> @Nullable FieldValue<ProjectileContext, T> mergeFv(@Nullable FieldValue<ProjectileContext, T> a,
                                                                          @Nullable FieldValue<ProjectileContext, T> b) {
        if (b == null) return a;
        if (a == null) return b;
        return a.or(b);
    }

    /**
     * Builder for the generic (key-less) default config - the base every type in a {@link ProjectileConfig}
     * inherits unless it overrides a knob. Presets define their projectile baseline here.
     */
    public static Builder builder() { return new Builder((Key) null); }
    public static Builder builder(Key key) { return new Builder(key); }
    /** Builder seeded from {@code base}'s fields, e.g. {@code builder(Vanilla18.projectileDefaults()).damage(5.0)}. */
    public static Builder builder(ProjectileTypeConfig base) { return new Builder(base); }
    /** A builder pre-filled with this config's fields. */
    public Builder toBuilder() { return new Builder(this); }

    /** Builder. Each knob takes a constant or a {@link ProjectileContext} lambda (per-launch). */
    public static final class Builder {
        private Key key;
        private FieldValue<ProjectileContext, Boolean> enabled;
        private FieldValue<ProjectileContext, BoundingBox> boundingBox;
        private FieldValue<ProjectileContext, Double> gravity;
        private FieldValue<ProjectileContext, Double> horizontalDrag;
        private FieldValue<ProjectileContext, Double> verticalDrag;
        private FieldValue<ProjectileContext, Double> spawnOffsetH;
        private FieldValue<ProjectileContext, Double> spawnOffsetV;
        private FieldValue<ProjectileContext, Double> spawnOffsetLateral;
        private FieldValue<ProjectileContext, Double> speed;
        private FieldValue<ProjectileContext, Double> spread;
        private FieldValue<ProjectileContext, Boolean> inheritMomentum;
        private FieldValue<ProjectileContext, Integer> shooterImmunityTicks;
        private FieldValue<ProjectileContext, Double> entityHitGrow;
        private FieldValue<ProjectileContext, BlockCollisionMode> blockCollision;
        private FieldValue<ProjectileContext, HitResponse> selfHit;
        private FieldValue<ProjectileContext, Integer> syncInterval;
        private FieldValue<ProjectileContext, KnockbackConfig> knockback;
        private FieldValue<ProjectileContext, KnockbackSource> knockbackSource;
        private FieldValue<ProjectileContext, Double> damage;
        private FieldValue<ProjectileContext, DamageType> damageType;
        private FieldValue<ProjectileContext, Boolean> removeOnEntityHit;
        private FieldValue<ProjectileContext, Boolean> removeOnBlockHit;
        private FieldValue<ProjectileContext, HitResponse> invulnHit;
        private FieldValue<ProjectileContext, PickupBox> pickupBox;
        private Function<ProjectileContext, ProjectileTypeConfig> subConfig;

        Builder(Key key) { this.key = key; }

        Builder(ProjectileTypeConfig c) {
            key = c.key;
            enabled = c.enabled;
            boundingBox = c.boundingBox;
            gravity = c.gravity;
            horizontalDrag = c.horizontalDrag;
            verticalDrag = c.verticalDrag;
            spawnOffsetH = c.spawnOffsetH;
            spawnOffsetV = c.spawnOffsetV;
            spawnOffsetLateral = c.spawnOffsetLateral;
            speed = c.speed;
            spread = c.spread;
            inheritMomentum = c.inheritMomentum;
            shooterImmunityTicks = c.shooterImmunityTicks;
            entityHitGrow = c.entityHitGrow;
            blockCollision = c.blockCollision;
            selfHit = c.selfHit;
            syncInterval = c.syncInterval;
            knockback = c.knockback;
            knockbackSource = c.knockbackSource;
            damage = c.damage;
            damageType = c.damageType;
            removeOnEntityHit = c.removeOnEntityHit;
            removeOnBlockHit = c.removeOnBlockHit;
            invulnHit = c.invulnHit;
            pickupBox = c.pickupBox;
            subConfig = c.subConfig;
        }

        public Builder key(Key k) { this.key = k; return this; }
        public Builder enabled(Boolean v) { enabled = FieldValue.constant(v); return this; }
        public Builder enabled(Function<ProjectileContext, Boolean> fn) { enabled = FieldValue.of(fn); return this; }
        public Builder boundingBox(BoundingBox v) { boundingBox = FieldValue.constant(v); return this; }
        public Builder boundingBox(double width, double height, double depth) { boundingBox = FieldValue.constant(new BoundingBox(width, height, depth)); return this; }
        public Builder boundingBox(Function<ProjectileContext, BoundingBox> fn) { boundingBox = FieldValue.of(fn); return this; }
        public Builder gravity(Double v) { gravity = FieldValue.constant(v); return this; }
        public Builder gravity(Function<ProjectileContext, Double> fn) { gravity = FieldValue.of(fn); return this; }
        public Builder horizontalDrag(Double v) { horizontalDrag = FieldValue.constant(v); return this; }
        public Builder horizontalDrag(Function<ProjectileContext, Double> fn) { horizontalDrag = FieldValue.of(fn); return this; }
        public Builder verticalDrag(Double v) { verticalDrag = FieldValue.constant(v); return this; }
        public Builder verticalDrag(Function<ProjectileContext, Double> fn) { verticalDrag = FieldValue.of(fn); return this; }
        /** Sets both spawn offsets at once: {@code h} forward along the shooter's look, {@code v} vertical from the eye. */
        public Builder spawnOffset(double h, double v) { spawnOffsetH = FieldValue.constant(h); spawnOffsetV = FieldValue.constant(v); return this; }
        /** Forward spawn offset along the shooter's look direction (blocks). */
        public Builder spawnOffsetH(Double v) { spawnOffsetH = FieldValue.constant(v); return this; }
        public Builder spawnOffsetH(Function<ProjectileContext, Double> fn) { spawnOffsetH = FieldValue.of(fn); return this; }
        /** Vertical spawn offset from the shooter's eye height (blocks). */
        public Builder spawnOffsetV(Double v) { spawnOffsetV = FieldValue.constant(v); return this; }
        public Builder spawnOffsetV(Function<ProjectileContext, Double> fn) { spawnOffsetV = FieldValue.of(fn); return this; }
        /** Sideways spawn offset perpendicular to the look (vanilla 1.8 throwing-hand shift {@code 0.16}; 26.1: 0). */
        public Builder spawnOffsetLateral(Double v) { spawnOffsetLateral = FieldValue.constant(v); return this; }
        public Builder spawnOffsetLateral(Function<ProjectileContext, Double> fn) { spawnOffsetLateral = FieldValue.of(fn); return this; }
        public Builder speed(Double v) { speed = FieldValue.constant(v); return this; }
        public Builder speed(Function<ProjectileContext, Double> fn) { speed = FieldValue.of(fn); return this; }
        public Builder spread(Double v) { spread = FieldValue.constant(v); return this; }
        public Builder spread(Function<ProjectileContext, Double> fn) { spread = FieldValue.of(fn); return this; }
        public Builder inheritMomentum(Boolean v) { inheritMomentum = FieldValue.constant(v); return this; }
        public Builder inheritMomentum(Function<ProjectileContext, Boolean> fn) { inheritMomentum = FieldValue.of(fn); return this; }
        public Builder shooterImmunityTicks(Integer v) { shooterImmunityTicks = FieldValue.constant(v); return this; }
        public Builder shooterImmunityTicks(Function<ProjectileContext, Integer> fn) { shooterImmunityTicks = FieldValue.of(fn); return this; }
        /** Entity-hit margin: grow the target's bbox by this on each side for the hit ray-test (vanilla 1.8 {@code 0.3}). */
        public Builder entityHitGrow(Double v) { entityHitGrow = FieldValue.constant(v); return this; }
        public Builder entityHitGrow(Function<ProjectileContext, Double> fn) { entityHitGrow = FieldValue.of(fn); return this; }
        /** How block hits are detected ({@link BlockCollisionMode}; default {@code SWEPT}). Vanilla 1.8 sets
         *  {@code RAYTRACE} for 1:1 block-edge agreement with a 1.8 client. */
        public Builder blockCollision(BlockCollisionMode v) { blockCollision = FieldValue.constant(v); return this; }
        public Builder blockCollision(Function<ProjectileContext, BlockCollisionMode> fn) { blockCollision = FieldValue.of(fn); return this; }
        /** What the projectile does when it hits its own shooter ({@link HitResponse}, default {@code HIT}):
         *  {@code PASS_THROUGH} = the 1.8 ender pearl / Hypixel "self does nothing"; {@code DEFLECT} = bounce off. */
        public Builder selfHit(HitResponse v) { selfHit = FieldValue.constant(v); return this; }
        public Builder selfHit(Function<ProjectileContext, HitResponse> fn) { selfHit = FieldValue.of(fn); return this; }
        public Builder syncInterval(Integer v) { syncInterval = FieldValue.constant(v); return this; }
        public Builder syncInterval(Function<ProjectileContext, Integer> fn) { syncInterval = FieldValue.of(fn); return this; }
        public Builder knockback(KnockbackConfig v) { knockback = FieldValue.constant(v); return this; }
        public Builder knockback(Function<ProjectileContext, KnockbackConfig> fn) { knockback = FieldValue.of(fn); return this; }
        public Builder knockbackSource(KnockbackSource v) { knockbackSource = FieldValue.constant(v); return this; }
        public Builder knockbackSource(Function<ProjectileContext, KnockbackSource> fn) { knockbackSource = FieldValue.of(fn); return this; }
        public Builder damage(Double v) { damage = FieldValue.constant(v); return this; }
        public Builder damage(Function<ProjectileContext, Double> fn) { damage = FieldValue.of(fn); return this; }
        public Builder damageType(DamageType v) { damageType = FieldValue.constant(v); return this; }
        public Builder damageType(Function<ProjectileContext, DamageType> fn) { damageType = FieldValue.of(fn); return this; }
        public Builder removeOnEntityHit(Boolean v) { removeOnEntityHit = FieldValue.constant(v); return this; }
        public Builder removeOnBlockHit(Boolean v) { removeOnBlockHit = FieldValue.constant(v); return this; }
        /** Bounce off (vanilla {@code motion *= -0.1}) instead of breaking on an entity hit that dealt no damage
         *  (arrows {@code true}, throwables {@code false}). */
        /** What the projectile does when its hit is blocked (target invulnerable/creative); vanilla arrows
         *  {@code DEFLECT}, throwables {@code DESTROY}. */
        public Builder invulnHit(HitResponse v) { invulnHit = FieldValue.constant(v); return this; }
        public Builder invulnHit(Function<ProjectileContext, HitResponse> fn) { invulnHit = FieldValue.of(fn); return this; }
        /** Pickup geometry for a collectable projectile (arrows); default {@link PickupBox#VANILLA}. */
        public Builder pickupBox(PickupBox v) { pickupBox = FieldValue.constant(v); return this; }
        public Builder pickupBox(Function<ProjectileContext, PickupBox> fn) { pickupBox = FieldValue.of(fn); return this; }
        public Builder subConfig(Function<ProjectileContext, ProjectileTypeConfig> fn) { subConfig = fn; return this; }

        public ProjectileTypeConfig build() { return new ProjectileTypeConfig(this); }
    }
}
