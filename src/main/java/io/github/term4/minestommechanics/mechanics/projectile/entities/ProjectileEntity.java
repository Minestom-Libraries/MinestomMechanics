package io.github.term4.minestommechanics.mechanics.projectile.entities;

import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig.BlockCollisionMode;
import net.minestom.server.ServerFlag;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.EntityCollisionResult;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.collision.ShapeImpl;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.projectile.ProjectileMeta;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.event.entity.projectile.ProjectileUncollideEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.utils.chunk.ChunkCache;
import net.minestom.server.utils.chunk.ChunkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Base projectile entity: 1.8-style physics, block stick, entity hits. See {@code docs/projectiles-design.md} for
 * the architecture and the FIX LEDGER this class cites ({@code F1}, {@code F3}, {@code F5}, ...). A stuck arrow keeps
 * its movement frozen but is NOT radio-silent: {@link #resyncStuck()} periodically re-asserts position + rotation,
 * mirroring vanilla's {@code ServerEntity} so a 1.8 client (through Via) self-heals - this supersedes the old
 * LegacyProjectileCompat hint/pullback plan (F7/F8/F12). Modern clients hold via the {@code inGround} metadata.
 *
 * <p>Velocity is blocks/<b>tick</b> internally (library convention); {@code super.velocity} mirrors it in
 * blocks/second so Minestom internals and external reads stay sane. Physics constants come from
 * {@link Aerodynamics} (set per type by the launcher), read live each tick - no TPS scaling yet, but all
 * timing is tick-denominated so a scaling pass stays mechanical.
 */
public abstract class ProjectileEntity extends Entity {

    /** Default ticks a freshly launched projectile cannot hit its own shooter (vanilla pass-through at spawn). */
    public static final int DEFAULT_SHOOTER_IMMUNITY_TICKS = 5;

    /** Default entity-hit margin: vanilla 1.8 {@code Entity{Arrow,Projectile}} grow the TARGET's bbox by {@code 0.3F}
     *  on EACH side and ray-test the projectile's center path. The Minkowski-equivalent is to grow our zero-size
     *  projectile box by 0.3 on each side (target+0.3 vs an arrow point == target vs an arrow point grown 0.3), i.e. a
     *  0.6-cube centered on the projectile. NOTE Minestom's {@code expand(0.3)} only adds 0.3 to the TOTAL size (±0.15)
     *  and offsets y - use {@code BoundingBox.growSymmetrically} which adds the amount to BOTH sides (±0.3, centered).
     *  The old {@code expand(0.1,0.3,0.1)} from {@code pos-0.3} reached only ~0.45 to the side, so arrows the 1.8 CLIENT
     *  predicts as a hit flew right by on the server -> the "arrow disappears for shooter / bounces for target" desync.
     *  Per-type override: {@code ProjectileTypeConfig.entityHitGrow} (26.1's modern preset uses a different margin). */
    public static final double DEFAULT_ENTITY_HIT_GROW = 0.3;

    /** Ticks the shooter is immune (configurable per type; set by the launcher). */
    protected int shooterImmunityTicks = DEFAULT_SHOOTER_IMMUNITY_TICKS;
    /** Alive-tick until which the shooter is immune again after a deflect (vanilla {@code as = 0} re-arm); see
     *  {@link #rearmShooterImmunity}. {@code <= 0} = no active re-arm (only the initial {@link #shooterImmunityTicks}). */
    private long shooterImmuneUntilAlive;
    /** Entity-hit margin grown onto the target on each side (configurable per type; stamped by the launcher). */
    protected double entityHitGrow = DEFAULT_ENTITY_HIT_GROW;
    /** Block-hit detection method (configurable per type; stamped by the launcher). {@code SWEPT} = Minestom physics
     *  (default); {@code RAYTRACE} = 1.8-faithful ray for 1.8-client edge agreement - see {@link BlockRaytrace}. */
    protected BlockCollisionMode blockCollision = BlockCollisionMode.SWEPT;
    /** Velocity in blocks/tick (library convention; {@code super.velocity} mirrors b/s). */
    protected Vec velocityBt = Vec.ZERO;
    /** Shooter position + view at launch, for knockback origin (vanilla uses the shooter, not the projectile). */
    protected Pos shooterOriginPos;
    protected @Nullable Entity shooter;

    // Stuck state: collisionDirection != null means stuck.
    /** Single-axis face normal of the hit surface (signed travel direction on the hit axis). */
    protected @Nullable Vec collisionDirection;
    /** Exact collision point - block lookups + unstuck checks. */
    protected @Nullable Point stuckCollisionPoint;
    /** Where the arrow is placed on stick: the physics-resolved position pulled back 0.05 along flight (vanilla, see
     *  {@link #stick}), so the tip pokes ~0.05 out of the block face. */
    private @Nullable Pos stuckPlacement;
    /** Counts ticks since sticking; drives the periodic stuck re-sync (vanilla parity - see {@link #tick}). */
    private long stuckSyncCounter;
    /** Throttles the in-flight teleport to the sync interval (velocity goes every tick - see {@link #synchronizePosition}). */
    private long flightSyncCounter;

    private @Nullable PhysicsResult previousPhysicsResult;
    private float prevYaw, prevPitch;
    private float stuckYaw, stuckPitch;
    private boolean justBecameStuck;
    /** Set by {@link #setDeflected} when a hit bounced the projectile this tick (velocity reversed, not removed); the
     *  entity-collision block then stops the forward move at the hit point so it doesn't overshoot before bouncing. */
    private boolean deflectedThisTick;
    /** Removal requested from inside movementTick; applied in {@link #tick} AFTER super.tick (post-touchTick) to
     *  avoid nulling {@code instance} before Minestom's {@code Entity.tick} reads it (NPE), and without entering the
     *  stuck/radio-silence state (which would skip the entity scheduler and never run a deferred remove). */
    private boolean pendingRemove;

    protected ProjectileEntity(@Nullable Entity shooter, @NotNull EntityType entityType) {
        super(entityType);
        this.shooter = shooter;
        this.shooterOriginPos = shooter != null ? shooter.getPosition() : Pos.ZERO;
        this.collidesWithEntities = false;
        this.preventBlockPlacement = false;
        if (getEntityMeta() instanceof ProjectileMeta meta) meta.setShooter(shooter);
        // F1: zero-size bounding box - collision points resolve exactly on block boundaries; even 0.01
        // overshoots and breaks the modern client's floor(pos) -> block -> inGround check.
        setBoundingBox(0, 0, 0);
    }

    // =========================================================================
    // Hooks for subclasses / launchers
    // =========================================================================

    /** Called when the projectile hits an entity. Return {@code true} to remove the projectile. */
    protected boolean onHit(@NotNull Entity entity) { return false; }

    /** Called when the projectile sticks in a block. Return {@code true} to remove the projectile. */
    protected boolean onStuck() { return false; }

    /** Called when the projectile unsticks (block broken). */
    protected void onUnstuck() {}

    /** Per-tick subclass update while alive (pickup, despawn timers); also runs while stuck. */
    protected void updateProjectile(long time) {}

    /** Whether this projectile can hit {@code entity} (shooter immunity is handled separately). */
    protected boolean canHit(@NotNull Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) return false;
        return !(entity instanceof Player p) || p.getGameMode() != GameMode.SPECTATOR;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public @Nullable Entity getShooter() { return shooter; }

    public @NotNull Pos getShooterOriginPos() { return shooterOriginPos; }

    /** Stamps the shooter's position/view at launch (knockback origin). */
    public void setShooterOriginPos(@NotNull Pos pos) { this.shooterOriginPos = pos; }

    public boolean isStuck() { return collisionDirection != null; }

    /** Sets how many ticks the shooter is immune to this projectile (launcher applies the resolved config). */
    public void setShooterImmunityTicks(int ticks) { this.shooterImmunityTicks = ticks; }

    /** Sets the entity-hit margin grown onto the target on each side (launcher applies the resolved config). */
    public void setEntityHitGrow(double grow) { this.entityHitGrow = grow; }

    /** Sets the block-hit detection method (launcher applies the resolved config; vanilla 1.8 = {@code RAYTRACE}). */
    public void setBlockCollision(BlockCollisionMode mode) { this.blockCollision = mode; }

    /** Re-arms shooter immunity for another {@link #shooterImmunityTicks} (vanilla {@code as = 0} after a deflect, so
     *  a bounced-back arrow can't instantly re-hit the shooter / loop on a self-deflect). */
    protected void rearmShooterImmunity() { this.shooterImmuneUntilAlive = getAliveTicks() + shooterImmunityTicks; }

    /** Flags that the projectile bounced this tick (see {@link #deflectedThisTick}); called by {@link ManagedProjectile#deflect}. */
    protected void setDeflected() { this.deflectedThisTick = true; }

    /** Velocity in blocks/tick. */
    public @NotNull Vec velocityBt() { return velocityBt; }

    /** Sets the velocity in blocks/tick (mirrors to {@code super.velocity} in blocks/second). */
    public void setVelocityBt(@NotNull Vec bt) {
        this.velocityBt = bt;
        this.velocity = bt.mul(ServerFlag.SERVER_TICKS_PER_SECOND);
    }

    // =========================================================================
    // Tick: vanilla-style periodic re-sync while stuck (supersedes the old F2 radio silence)
    // =========================================================================

    @Override
    public void tick(long time) {
        if (isStuck()) {
            if (isRemoved()) return;
            // Movement physics stay frozen (no super.tick / movementTick), but a stuck arrow is NOT radio-silent.
            // Vanilla's ServerEntity re-asserts position + ROTATION every updateInterval (its `!(entity instanceof
            // AbstractArrow)` PosRot branch) plus a periodic forced teleport - that continuous re-sync is the ONLY
            // thing that lets a 1.8 client (through Via) self-heal a mispredicted stuck arrow (relog angle, edge-hit
            // overshoot): the 1.8 client has no authoritative inGround stop, so with no re-syncs it never corrects.
            // We replicate it with a periodic absolute teleport (zero velocity). The modern client stays frozen via
            // the inGround metadata (ArrowEntity.onStuck), so each refresher is a no-op for it (same pos + rotation).
            // The counter starts at 0, so the first re-sync fires the tick AFTER sticking - the same one-tick delay
            // as the old Atlas teleport (don't snap to the hit before the client has seen it).
            updateProjectile(time);
            if (isRemoved()) return;
            if (stuckSyncCounter++ % Math.max(1L, getSynchronizationTicks()) == 0) resyncStuck();
            if (shouldUnstuck()) unstick();
            return;
        }
        super.tick(time);
        // Apply a removal requested during movementTick now that touchTick has run (instance still valid through it).
        if (pendingRemove) { pendingRemove = false; remove(); return; }
        if (!isRemoved()) updateProjectile(time);
    }

    @Override
    protected void movementTick() {
        this.gravityTickCount = isStuck() ? 0 : gravityTickCount + 1;
        if (vehicle != null || isStuck()) return;

        if (instance.isInVoid(position)) {
            pendingRemove = true;
            return;
        }

        // --- Block physics (swept) ---
        // Always run the swept physics: SWEPT mode sticks on it, and BOTH modes use it to bound the entity sweep below
        // (so an arrow can't hit an entity through a wall). In RAYTRACE mode the swept STICK decision is ignored - the
        // 1.8-faithful ray (BlockRaytrace, below) owns block hits - and the arrow flies the full move when the ray is
        // clear, so newPosition is position + velocity (world-border-clamped), not the swept-clipped position.
        boolean raytrace = blockCollision == BlockCollisionMode.RAYTRACE;
        ChunkCache blockGetter = new ChunkCache(instance, currentChunk, Block.AIR);
        PhysicsResult physics = CollisionUtils.handlePhysics(
                blockGetter, getBoundingBox(), position, velocityBt, previousPhysicsResult, true);
        this.previousPhysicsResult = physics;
        Pos newPosition = CollisionUtils.applyWorldBorder(instance.getWorldBorder(), position,
                raytrace ? position.add(velocityBt) : physics.newPosition());

        // --- Entity collision (swept alongside the block physics) ---
        // Vanilla 1.8: grow the TARGET by 0.3 on each side and ray-test the projectile's center path; we do the
        // Minkowski dual - grow the zero-size projectile box by 0.3 on each side (growSymmetrically, NOT expand - see
        // ENTITY_HIT_GROW) and sweep from the (un-offset) center along velocity.
        boolean shooterImmune = getAliveTicks() < shooterImmunityTicks || getAliveTicks() < shooterImmuneUntilAlive;
        Collection<EntityCollisionResult> hits = CollisionUtils.checkEntityCollisions(
                instance, boundingBox.growSymmetrically(entityHitGrow, entityHitGrow, entityHitGrow), position, velocityBt, 3,
                e -> e != this && !(shooterImmune && e == shooter) && canHit(e), physics);
        if (!hits.isEmpty()) {
            EntityCollisionResult hit = hits.iterator().next();
            var event = new ProjectileCollideWithEntityEvent(this, hit.collisionPoint().asPos(), hit.entity());
            EventDispatcher.call(event);
            if (!event.isCancelled() && onHit(hit.entity())) {
                pendingRemove = true; // removed in tick() after touchTick (see field doc)
                return;
            }
            // A deflect (onHit reversed the velocity but did not remove): stop the forward move at the hit point so the
            // arrow does not overshoot the target before bouncing back next tick (the reversed velocity carries it).
            if (deflectedThisTick) {
                deflectedThisTick = false;
                refreshPosition(hit.collisionPoint().asPos().withView(prevYaw, prevPitch), false, false);
                return;
            }
        }

        Chunk finalChunk = ChunkUtils.retrieve(instance, currentChunk, newPosition);
        if (!ChunkUtils.isLoaded(finalChunk)) return;

        this.justBecameStuck = false;

        // --- Block stick: hit block / point / axis (per the configured detection method) ---
        if (raytrace) {
            // 1.8-faithful: raytrace position -> position + velocity against block collision shapes (BlockRaytrace).
            // Place at the exact ray hit point (vanilla 1.8 sets locXYZ to movingobjectposition.pos), then stick()
            // applies the same 0.05 flight-direction pull-back as vanilla.
            BlockRaytrace.Hit hit = BlockRaytrace.cast(instance, currentChunk, position, velocityBt);
            if (hit != null) {
                stick(hit.block(), hit.point(), hit.axis(), hit.point().asPos());
                if (isRemoved() || pendingRemove) return; // breaker: stop physics, removal happens in tick()
            }
        } else if (physics.hasCollision()) {
            // SWEPT: per-axis Minestom collision shape -> hit block / point / axis.
            for (int axis = 0; axis < 3; axis++) {
                if (physics.collisionShapes()[axis] instanceof ShapeImpl) {
                    Point hitPoint = physics.collisionPoints()[axis];
                    Block hitBlock = instance.getBlock(hitPoint.sub(0, Vec.EPSILON, 0), Block.Getter.Condition.TYPE);
                    stick(hitBlock, hitPoint, axis, newPosition);
                    if (isRemoved() || pendingRemove) return; // breaker: stop physics, removal happens in tick()
                    break;
                }
            }
        }

        // --- Drag + gravity (live Aerodynamics, per tick) ---
        Aerodynamics aero = getAerodynamics();
        velocityBt = velocityBt
                .mul(aero.horizontalAirResistance(), aero.verticalAirResistance(), aero.horizontalAirResistance())
                .sub(0, hasNoGravity() ? 0 : aero.gravity(), 0);
        if (!justBecameStuck) this.velocity = velocityBt.mul(ServerFlag.SERVER_TICKS_PER_SECOND);
        // In RAYTRACE free-flight the arrow didn't stop on a block this tick (a hit returns above), so it's airborne;
        // the swept physics' onGround (a clipped floor it ignored) would be misleading.
        this.onGround = !raytrace && physics.isOnGround();

        // --- Rotation: displacement-based; latched at impact when sticking ---
        float yaw = prevYaw, pitch = prevPitch;
        if (justBecameStuck) {
            yaw = stuckYaw;
            pitch = stuckPitch;
        } else {
            Vec displacement = newPosition.sub(position).asVec();
            if (displacement.lengthSquared() > 1e-8) {
                double hl = Math.sqrt(displacement.x() * displacement.x() + displacement.z() * displacement.z());
                yaw = (float) Math.toDegrees(Math.atan2(displacement.x(), displacement.z()));
                pitch = (float) Math.toDegrees(Math.atan2(displacement.y(), hl));
            }
        }
        this.prevYaw = yaw;
        this.prevPitch = pitch;

        // F3: place at the physics-resolved position (not the collision point) - fixes modern clients seeing the
        // projectile float in front of the block face. On the stick tick, use the 0.05-pulled-back stuckPlacement.
        Pos place = justBecameStuck && stuckPlacement != null ? stuckPlacement : newPosition;
        refreshPosition(place.withView(yaw, pitch), false, false);
        if (justBecameStuck) this.lastSyncedPosition = getPosition();
    }

    private void stick(Block hitBlock, Point hitPoint, int hitAxis, Pos resolvedPosition) {
        var event = new ProjectileCollideWithBlockEvent(this, hitPoint.asPos(), hitBlock);
        EventDispatcher.call(event);
        if (event.isCancelled()) return;

        // Latch the flight rotation (for the stuck render) + the face normal, while velocity is still the flight value.
        if (velocityBt.lengthSquared() > 1e-8) {
            double hl = Math.sqrt(velocityBt.x() * velocityBt.x() + velocityBt.z() * velocityBt.z());
            stuckYaw = (float) Math.toDegrees(Math.atan2(velocityBt.x(), velocityBt.z()));
            stuckPitch = (float) Math.toDegrees(Math.atan2(velocityBt.y(), hl));
        } else {
            stuckYaw = prevYaw;
            stuckPitch = prevPitch;
        }
        Vec normal = switch (hitAxis) {
            case 0 -> new Vec(Math.signum(velocityBt.x()), 0, 0);
            case 1 -> new Vec(0, Math.signum(velocityBt.y()), 0);
            case 2 -> new Vec(0, 0, Math.signum(velocityBt.z()));
            default -> velocityBt.normalize();
        };

        // onStuck() fires the hit event + onImpact(null) and returns removeOnBlockHit: true = BREAK (vanilla
        // throwables die() on any hit and NEVER stick), false = STICK (arrow). Decide BEFORE entering the stuck
        // state, so a breaker doesn't go radio-silent (which would skip the scheduler and never get removed).
        boolean breakOnHit = onStuck();
        if (isRemoved()) return;
        if (breakOnHit) {
            setVelocityBt(Vec.ZERO);
            pendingRemove = true; // removed in tick() after touchTick - no stuck state, no radio silence
            return;
        }

        // Stick (arrows): enter the stuck state. The position/rotation re-sync (including the one-tick-delayed first
        // teleport - the old Atlas F4 trick) is driven by the periodic resyncStuck() in tick(); reset its counter so
        // the first re-assert fires next tick.
        this.collisionDirection = normal;
        this.stuckCollisionPoint = hitPoint;
        // Vanilla 0.05 pull-back along the flight direction (1.8 EntityArrow: locX -= motX/|mot| * 0.05; 26.1 a
        // per-axis signum*0.05): the arrow tip pokes ~0.05 out of the block face instead of flush. velocityBt is
        // still the flight value here (zeroed below).
        Vec flightDir = velocityBt.lengthSquared() > 1e-8 ? velocityBt.normalize() : normal;
        this.stuckPlacement = resolvedPosition.sub(flightDir.mul(0.05));
        this.stuckSyncCounter = 0;
        this.justBecameStuck = true;
        setNoGravity(true);
        setVelocityBt(Vec.ZERO);
    }

    private boolean shouldUnstuck() {
        if (collisionDirection == null || stuckCollisionPoint == null) return false;
        // Block broken = unstick. (An intersect-box check false-unsticks on fences/slabs - ledger.)
        Point intoBlock = stuckCollisionPoint.add(collisionDirection.mul(0.5));
        // Don't unstick while the chunk is unloaded/(re)loading - e.g. a shooter relog briefly empties it, which
        // would otherwise read AIR and drop the arrow (it falls away and becomes uncollectable).
        Chunk chunk = instance.getChunkAt(intoBlock.x(), intoBlock.z());
        if (chunk == null || !chunk.isLoaded()) return false;
        return instance.getBlock(intoBlock.asBlockVec(), Block.Getter.Condition.TYPE).isAir();
    }

    private void unstick() {
        EventDispatcher.call(new ProjectileUncollideEvent(this));
        collisionDirection = null;
        stuckCollisionPoint = null;
        setNoGravity(false);
        onUnstuck();
        // NOTE: a relogged 1.8 client may briefly "freeze then fall" here - it holds the arrow in a client-side
        // inGround state (which ignores position teleports) until its own block-changed check fires. This matches
        // vanilla 26 + Via (and even vanilla 1.8 occasionally), so we accept it rather than force a re-spawn.
    }

    // =========================================================================
    // Wire sync
    // =========================================================================

    /** The 1.8 wire wants blocks/tick; {@code velocityBt} already is. Reports ZERO while stuck so the stick-tick
     *  velocity broadcast (and any sync) never nudges a 1.8 client off the stuck position. */
    @Override
    protected Vec getVelocityForPacket() {
        return isStuck() ? Vec.ZERO : velocityBt;
    }

    // F5: absolute-teleport position sync. Minestom's default relative-move sync is invisible to 1.8 clients through
    // Via (ledger: "the arrow never moves"), so we send an absolute EntityTeleportPacket.
    @Override
    protected void synchronizePosition() {
        // While stuck, the periodic resyncStuck() in tick() owns the position broadcast (super.tick / this method
        // don't run then); this only fires on the stick tick itself, where we must NOT send (the resync's first
        // teleport next tick is the intended one-tick-delayed correction).
        if (isStuck()) {
            this.lastSyncedPosition = getPosition();
            return;
        }
        // O1: Entity.tick already broadcasts the velocity every tick right after this call, and the client predicts the
        // flight between teleports from it - so we only send the absolute TELEPORT, throttled to the sync interval
        // (vanilla sends position every updateInterval, not every tick). This override is invoked every tick - it can't
        // advance Minestom's private nextSynchronizationTick - so a counter throttles it. Cadence = syncInterval.
        if (flightSyncCounter++ % Math.max(1L, getSynchronizationTicks()) != 0) return;
        Pos pos = getPosition();
        sendPacketToViewersAndSelf(new EntityTeleportPacket(getEntityId(), pos, getVelocityForPacket(), 0, onGround));
        this.lastSyncedPosition = pos;
    }

    // Vanilla parity for a stuck arrow: re-assert absolute position + rotation (+ zero velocity) periodically so a
    // 1.8 client (via Via) self-heals any misprediction (relog angle, edge-hit overshoot). Mirrors what vanilla's
    // ServerEntity does every updateInterval; the modern client, frozen via inGround metadata, treats it as a no-op.
    private void resyncStuck() {
        Pos pos = getPosition();
        sendPacketToViewersAndSelf(new EntityTeleportPacket(getEntityId(), pos, Vec.ZERO, 0, true));
        sendPacketToViewersAndSelf(new EntityVelocityPacket(getEntityId(), Vec.ZERO));
        this.lastSyncedPosition = pos;
    }

    // F6/F13: new viewer (spawn, relog, chunk-cross). data > 0 makes ViaVersion include velocity bytes in the 1.8
    // SpawnObject. A stuck arrow spawns with zero velocity for BOTH clients (getVelocityForPacket reports zero while
    // stuck); the modern client then holds it via the inGround metadata, and the 1.8 client self-heals any spawn
    // misprediction from the periodic resyncStuck() teleports (vanilla parity - no per-version spawn split needed).
    @Override
    public void updateNewViewer(@NotNull Player player) {
        int data = shooter != null ? shooter.getEntityId() : 0;
        Pos pos = getPosition();
        player.sendPacket(new SpawnEntityPacket(getEntityId(), getUuid(), getEntityType(), pos, pos.yaw(), data, getVelocityForPacket()));
        player.sendPacket(getMetadataPacket());
    }
}
