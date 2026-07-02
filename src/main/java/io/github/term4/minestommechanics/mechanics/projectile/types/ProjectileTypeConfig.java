package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.config.TypeConfig;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileBehavior;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ProjectileContext;
import net.kyori.adventure.key.Key;
import net.minestom.server.collision.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Per-type projectile config, keyed by {@link #key()}: every value is a {@link FieldValue} resolved against a
 * {@link ProjectileContext} (constant or per-launch lambda), unset fields falling back per-type override -&gt;
 * {@link ProjectileConfig#defaults()} -&gt; the type's {@code defaultConfig()} -&gt; hard fallbacks. Launchers resolve it
 * once per launch and stamp the entity. Covers spawn/physics, hit knockback + damage, deflect, removal, behavior.
 */
public final class ProjectileTypeConfig extends TypeConfig<ProjectileContext, ProjectileTypeConfig> {

    /**
     * Where a hit's knockback originates (orthogonal to the {@link KnockbackConfig}'s {@code yawWeight}):
     * {@link #PROJECTILE} = origin the projectile, basis its flight (vanilla); {@link #SHOOTER} = origin the shooter,
     * basis its facing (carries the shooter as source like melee, so {@code yawWeight} picks aim vs direction).
     */
    public enum KnockbackSource { PROJECTILE, SHOOTER }

    /**
     * How a projectile responds to a hit it doesn't deal normally ({@link #selfHit}, or {@link InvulnResponse} when a hit
     * is rejected as invulnerable):
     * <ul>
     *   <li>{@link #HIT} - the normal hit (damage/KB/impact/break).</li>
     *   <li>{@link #PASS_THROUGH} - ignore the entity, keep flying unchanged (the 1.8 pearl through its thrower).</li>
     *   <li>{@link #DEFLECT} - bounce off per {@link #deflect}; no damage/KB/break.</li>
     *   <li>{@link #DESTROY} - break the projectile (impact effect fires, then removed); the vanilla throwable response.</li>
     * </ul>
     * "Bypass" (deal the hit despite invulnerability) lives on the damage type, not here.
     */
    public enum HitResponse { HIT, PASS_THROUGH, DEFLECT, DESTROY }

    /**
     * The {@link HitResponse} for the two invulnerability cases vanilla distinguishes: {@link #invulWindow} (i-frame window;
     * 1.8 + 26.1 arrow {@code DEFLECT}) and {@link #immune} (creative/spectator; 1.8 arrow {@code PASS_THROUGH}, 26.1 {@code DEFLECT}).
     * Creative/spectator aren't split (use a lambda if needed). Set via {@code invulnHit(...)} or {@link #of}.
     */
    public record InvulnResponse(HitResponse invulWindow, HitResponse immune) {
        /** The same response for both cases (e.g. {@code of(DESTROY)} = vanilla throwable, {@code of(DEFLECT)} = 26.1 arrow). */
        public static InvulnResponse of(HitResponse all) { return new InvulnResponse(all, all); }
    }

    /** When a projectile applies drag + gravity relative to its move: {@link #DRAG_AFTER_MOVE} (1.8) or {@link #DRAG_BEFORE_MOVE} (26.1). */
    public enum PhysicsOrder { DRAG_AFTER_MOVE, DRAG_BEFORE_MOVE }

    /**
     * Pickup geometry for a collectable projectile (arrows): collected when its {@code boxWidth x boxHeight} box
     * intersects a player's bbox inflated by {@code (inflateH, inflateV, inflateH)} (vanilla {@code grow(1, 0.5, 1)}).
     */
    public record PickupBox(double inflateH, double inflateV, double boxWidth, double boxHeight) {
        /** Vanilla 1.8 / 26.1 pickup geometry: player box inflated (1, 0.5, 1), arrow box 0.5 x 0.5. */
        public static final PickupBox VANILLA = new PickupBox(1.0, 0.5, 0.5, 0.5);
    }

    /**
     * How a {@link HitResponse#DEFLECT} transforms the velocity: scale by {@code multiplier} (negative reverses all axes),
     * then rotate the heading by {@code turn} + a random {@code [minJitter, maxJitter]} wobble (degrees). 1.8 = {@code deflect(-0.1)};
     * 26.1 = {@code deflect(-0.5, 0, -10, 10)}.
     */
    public record Deflect(double multiplier, double turn, double minJitter, double maxJitter) {
        /** Plain reverse + damp with no extra turn: {@code motion *= multiplier} (negative reverses). */
        public static Deflect of(double multiplier) { return new Deflect(multiplier, 0.0, 0.0, 0.0); }
    }

    public final @Nullable FieldValue<ProjectileContext, Boolean> enabled;
    public final @Nullable FieldValue<ProjectileContext, BoundingBox> boundingBox;
    public final @Nullable FieldValue<ProjectileContext, Double> gravity;
    public final @Nullable FieldValue<ProjectileContext, Double> horizontalDrag;
    public final @Nullable FieldValue<ProjectileContext, Double> verticalDrag;
    /** Forward spawn offset along the shooter's look direction (blocks). */
    public final @Nullable FieldValue<ProjectileContext, Double> spawnOffsetForward;
    /** Vertical spawn offset from the shooter's eye height (blocks). */
    public final @Nullable FieldValue<ProjectileContext, Double> spawnOffsetVertical;
    /** Sideways spawn offset perpendicular to the look (vanilla 1.8 throwing-hand shift, {@code 0.16}; 26.1: 0). */
    public final @Nullable FieldValue<ProjectileContext, Double> spawnOffsetSideways;
    public final @Nullable FieldValue<ProjectileContext, Double> speed;
    /** Degrees added to the aim pitch for the launch heading only (vanilla {@code shootFromRotation} shape - the horizontal keeps the un-offset pitch). Splash potion / XP bottle {@code -20}; {@code 0} = straight aim. */
    public final @Nullable FieldValue<ProjectileContext, Double> launchPitchOffset;
    public final @Nullable FieldValue<ProjectileContext, Double> spread;
    /** Fraction of the shooter's horizontal velocity (x/z) folded into the launch velocity. {@code 0} = none (1.8), {@code 1} = full (26.1). */
    public final @Nullable FieldValue<ProjectileContext, Double> momentumHorizontal;
    /** Fraction of the shooter's vertical velocity (y) folded in. {@code 0} = none (1.8); 26.1 folds it only when airborne (a lambda). */
    public final @Nullable FieldValue<ProjectileContext, Double> momentumVertical;
    public final @Nullable FieldValue<ProjectileContext, Integer> shooterImmunityTicks;
    /** Entity-hit margin: the target's bbox grows by this each side for the hit ray-test (vanilla {@code 0.3}). */
    public final @Nullable FieldValue<ProjectileContext, Double> entityHitGrow;
    /** What the projectile does when it hits its own shooter ({@link HitResponse}; default {@code HIT} = vanilla). */
    public final @Nullable FieldValue<ProjectileContext, HitResponse> selfHit;
    /** What the projectile does when it hits any other entity (default {@code HIT}); {@code DESTROY} = a pure impact trigger, no hit damage/KB (MineMen fireball). */
    public final @Nullable FieldValue<ProjectileContext, HitResponse> entityHit;
    /** Vanilla-style flight: per-tick movement packets to viewers (the 1.8/26 tracker shape). {@code false} (default) = silent flight, clients predict from the spawn velocity. */
    public final @Nullable FieldValue<ProjectileContext, Boolean> broadcastMovement;
    public final @Nullable FieldValue<ProjectileContext, Integer> syncInterval;
    /** In-flight velocity broadcast interval (ticks): {@code <= 0} = never (vanilla arrow, the edge-slide fix), {@code N} = every N ticks. */
    public final @Nullable FieldValue<ProjectileContext, Integer> velocitySyncInterval;
    /** When drag + gravity apply relative to the move ({@link PhysicsOrder}): 1.8 = {@code DRAG_AFTER_MOVE}, 26.1 = {@code DRAG_BEFORE_MOVE}. */
    public final @Nullable FieldValue<ProjectileContext, PhysicsOrder> physicsOrder;
    /** Shooter immunity model: {@code false} (1.8) = fixed {@link #shooterImmunityTicks}; {@code true} (26.1) = until the projectile leaves the shooter's box. */
    public final @Nullable FieldValue<ProjectileContext, Boolean> leftOwnerImmunity;
    /** Distance a stuck projectile is pulled back along its flight dir so the tip pokes out of the block face (vanilla {@code 0.05}). */
    public final @Nullable FieldValue<ProjectileContext, Double> stickPullback;
    /** Pickup cooldown (ticks) after a collectable projectile sticks (vanilla arrow {@code shake} = {@code 7}); arrow-only. */
    public final @Nullable FieldValue<ProjectileContext, Integer> shakeTicks;
    /** Explosion power on detonation (fireball-only); vanilla ghast {@code 1.0}, Hypixel {@code 2.0}. */
    public final @Nullable FieldValue<ProjectileContext, Double> explosionPower;
    /** Full-draw crit chance the bow rolls ({@code [0,1]}); vanilla {@code 1.0} = always. Arrow-launcher knob. */
    public final @Nullable FieldValue<ProjectileContext, Double> critChance;
    /** Pluggable {@link ProjectileBehavior} layered over the built-in effects (no subclassing). Default {@link ProjectileBehavior#NONE}. */
    public final @Nullable FieldValue<ProjectileContext, ProjectileBehavior> behavior;
    /** Splash-potion particle palette for MODERN viewers: {@code true} = the 1.8 liquid colors, {@code false} (default) = modern. Legacy viewers always get the raw 1.8 potion value (their client picks its own colors). */
    public final @Nullable FieldValue<ProjectileContext, Boolean> legacyPotionColors;
    public final @Nullable FieldValue<ProjectileContext, KnockbackConfig> knockback;
    public final @Nullable FieldValue<ProjectileContext, KnockbackSource> knockbackSource;
    public final @Nullable FieldValue<ProjectileContext, Double> damage;
    public final @Nullable FieldValue<ProjectileContext, DamageType> damageType;
    public final @Nullable FieldValue<ProjectileContext, Boolean> removeOnEntityHit;
    public final @Nullable FieldValue<ProjectileContext, Boolean> removeOnBlockHit;
    /** Response for a hit the target rejects as invulnerable ({@link InvulnResponse}). 1.8 arrow = {@code invulnHit(DEFLECT, PASS_THROUGH)}; throwables {@code invulnHit(DESTROY)} (default). */
    public final @Nullable FieldValue<ProjectileContext, InvulnResponse> invulnHit;
    /** How a {@link HitResponse#DEFLECT} transforms the velocity ({@link Deflect}). 1.8 = {@code deflect(-0.1)}, 26.1 = {@code deflect(-0.5, 0, -10, 10)}. Default {@code deflect(-0.1)}. */
    public final @Nullable FieldValue<ProjectileContext, Deflect> deflect;
    /** Pickup geometry (collectable projectiles only, e.g. arrows); default {@link PickupBox#VANILLA}. */
    public final @Nullable FieldValue<ProjectileContext, PickupBox> pickupBox;

    ProjectileTypeConfig(Builder b) {
        super(b.key, b.subConfig);
        this.enabled = b.enabled;
        this.boundingBox = b.boundingBox;
        this.gravity = b.gravity;
        this.horizontalDrag = b.horizontalDrag;
        this.verticalDrag = b.verticalDrag;
        this.spawnOffsetForward = b.spawnOffsetForward;
        this.spawnOffsetVertical = b.spawnOffsetVertical;
        this.spawnOffsetSideways = b.spawnOffsetSideways;
        this.speed = b.speed;
        this.launchPitchOffset = b.launchPitchOffset;
        this.spread = b.spread;
        this.momentumHorizontal = b.momentumHorizontal;
        this.momentumVertical = b.momentumVertical;
        this.shooterImmunityTicks = b.shooterImmunityTicks;
        this.entityHitGrow = b.entityHitGrow;
        this.selfHit = b.selfHit;
        this.entityHit = b.entityHit;
        this.broadcastMovement = b.broadcastMovement;
        this.syncInterval = b.syncInterval;
        this.velocitySyncInterval = b.velocitySyncInterval;
        this.physicsOrder = b.physicsOrder;
        this.leftOwnerImmunity = b.leftOwnerImmunity;
        this.stickPullback = b.stickPullback;
        this.shakeTicks = b.shakeTicks;
        this.explosionPower = b.explosionPower;
        this.critChance = b.critChance;
        this.behavior = b.behavior;
        this.legacyPotionColors = b.legacyPotionColors;
        this.knockback = b.knockback;
        this.knockbackSource = b.knockbackSource;
        this.damage = b.damage;
        this.damageType = b.damageType;
        this.removeOnEntityHit = b.removeOnEntityHit;
        this.removeOnBlockHit = b.removeOnBlockHit;
        this.invulnHit = b.invulnHit;
        this.deflect = b.deflect;
        this.pickupBox = b.pickupBox;
    }

    /** Merges this config over {@code base}: this config's set fields win, unset fields fall back per resolution. */
    public ProjectileTypeConfig fromBase(ProjectileTypeConfig base) {
        Builder b = new Builder(key() != null ? key() : base.key());
        b.subConfig = subConfig != null ? subConfig : base.subConfig;
        b.enabled = merge(enabled, base.enabled);
        b.boundingBox = merge(boundingBox, base.boundingBox);
        b.gravity = merge(gravity, base.gravity);
        b.horizontalDrag = merge(horizontalDrag, base.horizontalDrag);
        b.verticalDrag = merge(verticalDrag, base.verticalDrag);
        b.spawnOffsetForward = merge(spawnOffsetForward, base.spawnOffsetForward);
        b.spawnOffsetVertical = merge(spawnOffsetVertical, base.spawnOffsetVertical);
        b.spawnOffsetSideways = merge(spawnOffsetSideways, base.spawnOffsetSideways);
        b.speed = merge(speed, base.speed);
        b.launchPitchOffset = merge(launchPitchOffset, base.launchPitchOffset);
        b.spread = merge(spread, base.spread);
        b.momentumHorizontal = merge(momentumHorizontal, base.momentumHorizontal);
        b.momentumVertical = merge(momentumVertical, base.momentumVertical);
        b.shooterImmunityTicks = merge(shooterImmunityTicks, base.shooterImmunityTicks);
        b.entityHitGrow = merge(entityHitGrow, base.entityHitGrow);
        b.selfHit = merge(selfHit, base.selfHit);
        b.entityHit = merge(entityHit, base.entityHit);
        b.broadcastMovement = merge(broadcastMovement, base.broadcastMovement);
        b.syncInterval = merge(syncInterval, base.syncInterval);
        b.velocitySyncInterval = merge(velocitySyncInterval, base.velocitySyncInterval);
        b.physicsOrder = merge(physicsOrder, base.physicsOrder);
        b.leftOwnerImmunity = merge(leftOwnerImmunity, base.leftOwnerImmunity);
        b.stickPullback = merge(stickPullback, base.stickPullback);
        b.shakeTicks = merge(shakeTicks, base.shakeTicks);
        b.explosionPower = merge(explosionPower, base.explosionPower);
        b.critChance = merge(critChance, base.critChance);
        b.behavior = merge(behavior, base.behavior);
        b.legacyPotionColors = merge(legacyPotionColors, base.legacyPotionColors);
        b.knockback = merge(knockback, base.knockback);
        b.knockbackSource = merge(knockbackSource, base.knockbackSource);
        b.damage = merge(damage, base.damage);
        b.damageType = merge(damageType, base.damageType);
        b.removeOnEntityHit = merge(removeOnEntityHit, base.removeOnEntityHit);
        b.removeOnBlockHit = merge(removeOnBlockHit, base.removeOnBlockHit);
        b.invulnHit = merge(invulnHit, base.invulnHit);
        b.deflect = merge(deflect, base.deflect);
        b.pickupBox = merge(pickupBox, base.pickupBox);
        return b.build();
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
        private Function<ProjectileContext, ProjectileTypeConfig> subConfig;
        private FieldValue<ProjectileContext, Boolean> enabled;
        private FieldValue<ProjectileContext, BoundingBox> boundingBox;
        private FieldValue<ProjectileContext, Double> gravity;
        private FieldValue<ProjectileContext, Double> horizontalDrag;
        private FieldValue<ProjectileContext, Double> verticalDrag;
        private FieldValue<ProjectileContext, Double> spawnOffsetForward;
        private FieldValue<ProjectileContext, Double> spawnOffsetVertical;
        private FieldValue<ProjectileContext, Double> spawnOffsetSideways;
        private FieldValue<ProjectileContext, Double> speed;
        private FieldValue<ProjectileContext, Double> launchPitchOffset;
        private FieldValue<ProjectileContext, Double> spread;
        private FieldValue<ProjectileContext, Double> momentumHorizontal;
        private FieldValue<ProjectileContext, Double> momentumVertical;
        private FieldValue<ProjectileContext, Integer> shooterImmunityTicks;
        private FieldValue<ProjectileContext, Double> entityHitGrow;
        private FieldValue<ProjectileContext, HitResponse> selfHit;
        private FieldValue<ProjectileContext, HitResponse> entityHit;
        private FieldValue<ProjectileContext, Boolean> broadcastMovement;
        private FieldValue<ProjectileContext, Integer> syncInterval;
        private FieldValue<ProjectileContext, Integer> velocitySyncInterval;
        private FieldValue<ProjectileContext, PhysicsOrder> physicsOrder;
        private FieldValue<ProjectileContext, Boolean> leftOwnerImmunity;
        private FieldValue<ProjectileContext, Double> stickPullback;
        private FieldValue<ProjectileContext, Integer> shakeTicks;
        private FieldValue<ProjectileContext, Double> explosionPower;
        private FieldValue<ProjectileContext, Double> critChance;
        private FieldValue<ProjectileContext, ProjectileBehavior> behavior;
        private FieldValue<ProjectileContext, Boolean> legacyPotionColors;
        private FieldValue<ProjectileContext, KnockbackConfig> knockback;
        private FieldValue<ProjectileContext, KnockbackSource> knockbackSource;
        private FieldValue<ProjectileContext, Double> damage;
        private FieldValue<ProjectileContext, DamageType> damageType;
        private FieldValue<ProjectileContext, Boolean> removeOnEntityHit;
        private FieldValue<ProjectileContext, Boolean> removeOnBlockHit;
        private FieldValue<ProjectileContext, InvulnResponse> invulnHit;
        private FieldValue<ProjectileContext, Deflect> deflect;
        private FieldValue<ProjectileContext, PickupBox> pickupBox;

        Builder(Key key) { this.key = key; }

        Builder(ProjectileTypeConfig c) {
            key = c.key();
            subConfig = c.subConfig;
            enabled = c.enabled;
            boundingBox = c.boundingBox;
            gravity = c.gravity;
            horizontalDrag = c.horizontalDrag;
            verticalDrag = c.verticalDrag;
            spawnOffsetForward = c.spawnOffsetForward;
            spawnOffsetVertical = c.spawnOffsetVertical;
            spawnOffsetSideways = c.spawnOffsetSideways;
            speed = c.speed;
            launchPitchOffset = c.launchPitchOffset;
            spread = c.spread;
            momentumHorizontal = c.momentumHorizontal;
            momentumVertical = c.momentumVertical;
            shooterImmunityTicks = c.shooterImmunityTicks;
            entityHitGrow = c.entityHitGrow;
            selfHit = c.selfHit;
            entityHit = c.entityHit;
            broadcastMovement = c.broadcastMovement;
            syncInterval = c.syncInterval;
            velocitySyncInterval = c.velocitySyncInterval;
            physicsOrder = c.physicsOrder;
            leftOwnerImmunity = c.leftOwnerImmunity;
            stickPullback = c.stickPullback;
            shakeTicks = c.shakeTicks;
            explosionPower = c.explosionPower;
            critChance = c.critChance;
            behavior = c.behavior;
            legacyPotionColors = c.legacyPotionColors;
            knockback = c.knockback;
            knockbackSource = c.knockbackSource;
            damage = c.damage;
            damageType = c.damageType;
            removeOnEntityHit = c.removeOnEntityHit;
            removeOnBlockHit = c.removeOnBlockHit;
            invulnHit = c.invulnHit;
            deflect = c.deflect;
            pickupBox = c.pickupBox;
        }

        public Builder key(Key k) { this.key = k; return this; }
        public Builder enabled(Boolean v) { enabled = FieldValue.constant(v); return this; }
        public Builder enabled(Function<ProjectileContext, Boolean> fn) { enabled = FieldValue.of(fn); return this; }
        public Builder enabled(Boolean fallback, Function<ProjectileContext, Boolean> fn) { enabled = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder boundingBox(BoundingBox v) { boundingBox = FieldValue.constant(v); return this; }
        public Builder boundingBox(double width, double height, double depth) { boundingBox = FieldValue.constant(new BoundingBox(width, height, depth)); return this; }
        public Builder boundingBox(Function<ProjectileContext, BoundingBox> fn) { boundingBox = FieldValue.of(fn); return this; }
        public Builder boundingBox(BoundingBox fallback, Function<ProjectileContext, BoundingBox> fn) { boundingBox = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder gravity(Double v) { gravity = FieldValue.constant(v); return this; }
        public Builder gravity(Function<ProjectileContext, Double> fn) { gravity = FieldValue.of(fn); return this; }
        public Builder gravity(Double fallback, Function<ProjectileContext, Double> fn) { gravity = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder horizontalDrag(Double v) { horizontalDrag = FieldValue.constant(v); return this; }
        public Builder horizontalDrag(Function<ProjectileContext, Double> fn) { horizontalDrag = FieldValue.of(fn); return this; }
        public Builder horizontalDrag(Double fallback, Function<ProjectileContext, Double> fn) { horizontalDrag = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder verticalDrag(Double v) { verticalDrag = FieldValue.constant(v); return this; }
        public Builder verticalDrag(Function<ProjectileContext, Double> fn) { verticalDrag = FieldValue.of(fn); return this; }
        public Builder verticalDrag(Double fallback, Function<ProjectileContext, Double> fn) { verticalDrag = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Sets all three spawn offsets at once: {@code forward} along the look, {@code vertical} from the eye, {@code sideways} perpendicular. */
        public Builder spawnOffset(double forward, double vertical, double sideways) {
            spawnOffsetForward = FieldValue.constant(forward);
            spawnOffsetVertical = FieldValue.constant(vertical);
            spawnOffsetSideways = FieldValue.constant(sideways);
            return this;
        }
        public Builder spawnOffsetForward(Double v) { spawnOffsetForward = FieldValue.constant(v); return this; }
        public Builder spawnOffsetForward(Function<ProjectileContext, Double> fn) { spawnOffsetForward = FieldValue.of(fn); return this; }
        public Builder spawnOffsetForward(Double fallback, Function<ProjectileContext, Double> fn) { spawnOffsetForward = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder spawnOffsetVertical(Double v) { spawnOffsetVertical = FieldValue.constant(v); return this; }
        public Builder spawnOffsetVertical(Function<ProjectileContext, Double> fn) { spawnOffsetVertical = FieldValue.of(fn); return this; }
        public Builder spawnOffsetVertical(Double fallback, Function<ProjectileContext, Double> fn) { spawnOffsetVertical = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder spawnOffsetSideways(Double v) { spawnOffsetSideways = FieldValue.constant(v); return this; }
        public Builder spawnOffsetSideways(Function<ProjectileContext, Double> fn) { spawnOffsetSideways = FieldValue.of(fn); return this; }
        public Builder spawnOffsetSideways(Double fallback, Function<ProjectileContext, Double> fn) { spawnOffsetSideways = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder speed(Double v) { speed = FieldValue.constant(v); return this; }
        public Builder speed(Function<ProjectileContext, Double> fn) { speed = FieldValue.of(fn); return this; }
        public Builder speed(Double fallback, Function<ProjectileContext, Double> fn) { speed = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Degrees added to the aim pitch for the launch heading only (splash potion / XP bottle {@code -20}; {@code 0} = straight aim). */
        public Builder launchPitchOffset(Double v) { launchPitchOffset = FieldValue.constant(v); return this; }
        public Builder launchPitchOffset(Function<ProjectileContext, Double> fn) { launchPitchOffset = FieldValue.of(fn); return this; }
        public Builder launchPitchOffset(Double fallback, Function<ProjectileContext, Double> fn) { launchPitchOffset = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder spread(Double v) { spread = FieldValue.constant(v); return this; }
        public Builder spread(Function<ProjectileContext, Double> fn) { spread = FieldValue.of(fn); return this; }
        public Builder spread(Double fallback, Function<ProjectileContext, Double> fn) { spread = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Sets both shooter-momentum scales at once (fraction of the shooter's client motion folded in: x/z, y). */
        public Builder momentum(double horizontal, double vertical) { momentumHorizontal = FieldValue.constant(horizontal); momentumVertical = FieldValue.constant(vertical); return this; }
        /** Horizontal shooter-momentum scale (fraction of the shooter's client {@code positionDelta} x/z; {@code 0} = none, {@code 1} = full). */
        public Builder momentumHorizontal(Double v) { momentumHorizontal = FieldValue.constant(v); return this; }
        public Builder momentumHorizontal(Function<ProjectileContext, Double> fn) { momentumHorizontal = FieldValue.of(fn); return this; }
        public Builder momentumHorizontal(Double fallback, Function<ProjectileContext, Double> fn) { momentumHorizontal = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Vertical shooter-momentum scale (fraction of the client motion y; for 26.1's airborne-only, use a lambda). */
        public Builder momentumVertical(Double v) { momentumVertical = FieldValue.constant(v); return this; }
        public Builder momentumVertical(Function<ProjectileContext, Double> fn) { momentumVertical = FieldValue.of(fn); return this; }
        public Builder momentumVertical(Double fallback, Function<ProjectileContext, Double> fn) { momentumVertical = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder shooterImmunityTicks(Integer v) { shooterImmunityTicks = FieldValue.constant(v); return this; }
        public Builder shooterImmunityTicks(Function<ProjectileContext, Integer> fn) { shooterImmunityTicks = FieldValue.of(fn); return this; }
        public Builder shooterImmunityTicks(Integer fallback, Function<ProjectileContext, Integer> fn) { shooterImmunityTicks = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder entityHitGrow(Double v) { entityHitGrow = FieldValue.constant(v); return this; }
        public Builder entityHitGrow(Function<ProjectileContext, Double> fn) { entityHitGrow = FieldValue.of(fn); return this; }
        public Builder entityHitGrow(Double fallback, Function<ProjectileContext, Double> fn) { entityHitGrow = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** What the projectile does when it hits its own shooter ({@link HitResponse}, default {@code HIT}):
         *  {@code PASS_THROUGH} = the 1.8 ender pearl / Hypixel "self does nothing"; {@code DEFLECT} = bounce off. */
        public Builder selfHit(HitResponse v) { selfHit = FieldValue.constant(v); return this; }
        public Builder selfHit(Function<ProjectileContext, HitResponse> fn) { selfHit = FieldValue.of(fn); return this; }
        public Builder selfHit(HitResponse fallback, Function<ProjectileContext, HitResponse> fn) { selfHit = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** What the projectile does when it hits any other entity (default {@code HIT}): {@code DESTROY} = impact trigger only, no hit damage/KB. */
        public Builder entityHit(HitResponse v) { entityHit = FieldValue.constant(v); return this; }
        public Builder entityHit(Function<ProjectileContext, HitResponse> fn) { entityHit = FieldValue.of(fn); return this; }
        public Builder entityHit(HitResponse fallback, Function<ProjectileContext, HitResponse> fn) { entityHit = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Vanilla-style flight: per-tick movement packets to viewers ({@code true} = the 1.8/26 tracker shape). */
        public Builder broadcastMovement(Boolean v) { broadcastMovement = FieldValue.constant(v); return this; }
        public Builder broadcastMovement(Function<ProjectileContext, Boolean> fn) { broadcastMovement = FieldValue.of(fn); return this; }
        public Builder broadcastMovement(Boolean fallback, Function<ProjectileContext, Boolean> fn) { broadcastMovement = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder syncInterval(Integer v) { syncInterval = FieldValue.constant(v); return this; }
        public Builder syncInterval(Function<ProjectileContext, Integer> fn) { syncInterval = FieldValue.of(fn); return this; }
        public Builder syncInterval(Integer fallback, Function<ProjectileContext, Integer> fn) { syncInterval = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder velocitySyncInterval(Integer v) { velocitySyncInterval = FieldValue.constant(v); return this; }
        public Builder velocitySyncInterval(Function<ProjectileContext, Integer> fn) { velocitySyncInterval = FieldValue.of(fn); return this; }
        public Builder velocitySyncInterval(Integer fallback, Function<ProjectileContext, Integer> fn) { velocitySyncInterval = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder physicsOrder(PhysicsOrder v) { physicsOrder = FieldValue.constant(v); return this; }
        public Builder physicsOrder(Function<ProjectileContext, PhysicsOrder> fn) { physicsOrder = FieldValue.of(fn); return this; }
        public Builder physicsOrder(PhysicsOrder fallback, Function<ProjectileContext, PhysicsOrder> fn) { physicsOrder = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder leftOwnerImmunity(Boolean v) { leftOwnerImmunity = FieldValue.constant(v); return this; }
        public Builder leftOwnerImmunity(Function<ProjectileContext, Boolean> fn) { leftOwnerImmunity = FieldValue.of(fn); return this; }
        public Builder leftOwnerImmunity(Boolean fallback, Function<ProjectileContext, Boolean> fn) { leftOwnerImmunity = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder stickPullback(Double v) { stickPullback = FieldValue.constant(v); return this; }
        public Builder stickPullback(Function<ProjectileContext, Double> fn) { stickPullback = FieldValue.of(fn); return this; }
        public Builder stickPullback(Double fallback, Function<ProjectileContext, Double> fn) { stickPullback = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder shakeTicks(Integer v) { shakeTicks = FieldValue.constant(v); return this; }
        public Builder shakeTicks(Function<ProjectileContext, Integer> fn) { shakeTicks = FieldValue.of(fn); return this; }
        public Builder shakeTicks(Integer fallback, Function<ProjectileContext, Integer> fn) { shakeTicks = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder explosionPower(Double v) { explosionPower = FieldValue.constant(v); return this; }
        public Builder explosionPower(Function<ProjectileContext, Double> fn) { explosionPower = FieldValue.of(fn); return this; }
        public Builder explosionPower(Double fallback, Function<ProjectileContext, Double> fn) { explosionPower = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Full-draw crit chance the bow rolls ({@code [0,1]}); vanilla {@code 1.0}. */
        public Builder critChance(Double v) { critChance = FieldValue.constant(v); return this; }
        public Builder critChance(Function<ProjectileContext, Double> fn) { critChance = FieldValue.of(fn); return this; }
        public Builder critChance(Double fallback, Function<ProjectileContext, Double> fn) { critChance = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Pluggable {@link ProjectileBehavior} (onImpact/onStuck/onUnstuck/onTick) layered over the built-in effects. */
        public Builder behavior(ProjectileBehavior v) { behavior = FieldValue.constant(v); return this; }
        public Builder behavior(Function<ProjectileContext, ProjectileBehavior> fn) { behavior = FieldValue.of(fn); return this; }
        public Builder behavior(ProjectileBehavior fallback, Function<ProjectileContext, ProjectileBehavior> fn) { behavior = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Splash-potion particle palette for modern viewers: {@code true} = the 1.8 liquid colors. */
        public Builder legacyPotionColors(Boolean v) { legacyPotionColors = FieldValue.constant(v); return this; }
        public Builder legacyPotionColors(Function<ProjectileContext, Boolean> fn) { legacyPotionColors = FieldValue.of(fn); return this; }
        public Builder legacyPotionColors(Boolean fallback, Function<ProjectileContext, Boolean> fn) { legacyPotionColors = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder knockback(KnockbackConfig v) { knockback = FieldValue.constant(v); return this; }
        public Builder knockback(Function<ProjectileContext, KnockbackConfig> fn) { knockback = FieldValue.of(fn); return this; }
        public Builder knockback(KnockbackConfig fallback, Function<ProjectileContext, KnockbackConfig> fn) { knockback = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder knockbackSource(KnockbackSource v) { knockbackSource = FieldValue.constant(v); return this; }
        public Builder knockbackSource(Function<ProjectileContext, KnockbackSource> fn) { knockbackSource = FieldValue.of(fn); return this; }
        public Builder knockbackSource(KnockbackSource fallback, Function<ProjectileContext, KnockbackSource> fn) { knockbackSource = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder damage(Double v) { damage = FieldValue.constant(v); return this; }
        public Builder damage(Function<ProjectileContext, Double> fn) { damage = FieldValue.of(fn); return this; }
        public Builder damage(Double fallback, Function<ProjectileContext, Double> fn) { damage = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder damageType(DamageType v) { damageType = FieldValue.constant(v); return this; }
        public Builder damageType(Function<ProjectileContext, DamageType> fn) { damageType = FieldValue.of(fn); return this; }
        public Builder damageType(DamageType fallback, Function<ProjectileContext, DamageType> fn) { damageType = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder removeOnEntityHit(Boolean v) { removeOnEntityHit = FieldValue.constant(v); return this; }
        public Builder removeOnBlockHit(Boolean v) { removeOnBlockHit = FieldValue.constant(v); return this; }
        /** Rejected-hit response, same for the invul window and an immune target; e.g. {@code invulnHit(DESTROY)} (throwable). */
        public Builder invulnHit(HitResponse all) { invulnHit = FieldValue.constant(InvulnResponse.of(all)); return this; }
        /** Rejected-hit response split: invul window vs immune (creative/spectator); e.g. 1.8 arrow {@code invulnHit(DEFLECT, PASS_THROUGH)}. */
        public Builder invulnHit(HitResponse invulWindow, HitResponse immune) { invulnHit = FieldValue.constant(new InvulnResponse(invulWindow, immune)); return this; }
        /** Per-launch lambda returning the {@link InvulnResponse} (build it with {@link InvulnResponse#of}). */
        public Builder invulnHit(Function<ProjectileContext, InvulnResponse> fn) { invulnHit = FieldValue.of(fn); return this; }
        public Builder invulnHit(InvulnResponse fallback, Function<ProjectileContext, InvulnResponse> fn) { invulnHit = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** A {@code DEFLECT} that just scales velocity by {@code multiplier} (negative reverses; vanilla 1.8 = {@code deflect(-0.1)}). */
        public Builder deflect(double multiplier) { deflect = FieldValue.constant(Deflect.of(multiplier)); return this; }
        /** A {@code DEFLECT} scaling velocity by {@code multiplier} + rotating the heading by {@code turn} +- a random {@code [min,max]} wobble (26.1 = {@code deflect(-0.5, 0, -10, 10)}). */
        public Builder deflect(double multiplier, double turn, double min, double max) { deflect = FieldValue.constant(new Deflect(multiplier, turn, min, max)); return this; }
        public Builder deflect(Function<ProjectileContext, Deflect> fn) { deflect = FieldValue.of(fn); return this; }
        public Builder deflect(Deflect fallback, Function<ProjectileContext, Deflect> fn) { deflect = FieldValue.ofWithFallback(fallback, fn); return this; }
        /** Pickup geometry for a collectable projectile (arrows); default {@link PickupBox#VANILLA}. */
        public Builder pickupBox(PickupBox v) { pickupBox = FieldValue.constant(v); return this; }
        public Builder pickupBox(Function<ProjectileContext, PickupBox> fn) { pickupBox = FieldValue.of(fn); return this; }
        public Builder pickupBox(PickupBox fallback, Function<ProjectileContext, PickupBox> fn) { pickupBox = FieldValue.ofWithFallback(fallback, fn); return this; }
        public Builder subConfig(Function<ProjectileContext, ProjectileTypeConfig> fn) { subConfig = fn; return this; }

        public ProjectileTypeConfig build() { return new ProjectileTypeConfig(this); }
    }
}
