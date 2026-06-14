package io.github.term4.minestommechanics.mechanics.projectile;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.api.event.ProjectileLaunchEvent;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ProjectileContext;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ResolvedFlight;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileType;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import io.github.term4.minestommechanics.tracking.MotionTracker;
import net.kyori.adventure.key.Key;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Projectile system: resolves a {@link ProjectileSnapshot} into a spawn + velocity, fires
 * {@link ProjectileLaunchEvent}, and spawns the entity. Mirrors {@code DamageSystem} - the {@link ProjectileConfig}
 * decides what runs (every type with a {@code typeConfigs} entry is enabled at install; the per-type
 * {@code enabled} knob is the per-launch gate). Self-driven types wire their own item triggers in
 * {@link ProjectileType#enable} and call {@link #launch}.
 */
public final class ProjectileSystem {

    private final ProjectileConfig config;
    private final Services services;
    private final MinestomMechanics mm;
    private final EventNode<@NotNull Event> node;
    private final Map<Key, ProjectileType> types = new ConcurrentHashMap<>();
    private final java.util.Set<Key> enabled = ConcurrentHashMap.newKeySet();

    public ProjectileSystem(MinestomMechanics mm, ProjectileConfig config) {
        this.mm = mm;
        this.config = config;
        this.services = mm.services();
        this.node = EventNode.all("mm:projectile");
    }

    public ProjectileConfig config() { return config; }
    public EventNode<@NotNull Event> node() { return node; }

    /** Effective config for a snapshot carrying none: the shooter's scoped profile, else the install config. */
    private ProjectileConfig configFor(@Nullable Entity shooter) {
        ProjectileConfig scoped = mm.profiles().projectilesFor(shooter);
        return scoped != null ? scoped : config;
    }

    /**
     * Launches a projectile from a snapshot: resolves its config, computes spawn + velocity (snapshot overrides
     * win, else aim x speed x power + spread + shooter momentum, eye + offsets), fires the cancellable
     * {@link ProjectileLaunchEvent}, then stamps and spawns the entity. Returns the entity, or {@code null} if the
     * type is disabled, the launch was cancelled, or the shooter has no instance.
     */
    public @Nullable ProjectileEntity launch(ProjectileSnapshot snap) {
        ProjectileSnapshot working = snap.config() != null ? snap : snap.withConfig(configFor(snap.shooter()));
        ProjectileContext ctx = ProjectileContext.of(working, services);
        ProjectileTypeConfig effectiveConfig = ctx.typeConfig();
        ResolvedFlight flight = ProjectileConfigResolver.resolveFlight(effectiveConfig, ctx);
        if (!flight.enabled()) return null;

        Entity shooter = working.shooter();
        Instance instance = shooter.getInstance();
        if (instance == null) return null;

        Pos spawnPos = working.spawnPos() != null ? working.spawnPos() : spawnPos(shooter, flight);
        Vec velocity = working.velocity() != null ? working.velocity() : launchVelocity(shooter, flight, working.power());

        // Build + configure the entity (HIT knobs resolve later, at impact, from effectiveConfig), then fire the one
        // launch event with it: cancel to discard before it spawns, mutate spawn/velocity to redirect, or attach.
        ProjectileEntity entity = working.type().createEntity(shooter, working, effectiveConfig);
        entity.setBoundingBox(flight.boundingBox().width(), flight.boundingBox().height(), flight.boundingBox().depth());
        entity.setAerodynamics(new Aerodynamics(flight.gravity(), flight.verticalDrag(), flight.horizontalDrag()));
        entity.setSynchronizationTicks(flight.syncInterval());
        entity.setShooterImmunityTicks(flight.shooterImmunityTicks());
        entity.setEntityHitGrow(flight.entityHitGrow());
        entity.setBlockCollision(flight.blockCollision());
        // Arrow-specific pickup geometry (resolved once at launch); the entity keeps its vanilla default if unset.
        if (entity instanceof io.github.term4.minestommechanics.mechanics.projectile.entities.ArrowEntity arrow) {
            ProjectileTypeConfig.PickupBox pb = effectiveConfig.pickupBox(ctx);
            if (pb != null) arrow.setPickupBox(pb);
        }

        ProjectileLaunchEvent event = new ProjectileLaunchEvent(working, entity, spawnPos, velocity);
        EventDispatcher.call(event);
        if (event.isCancelled()) return null; // entity discarded - never spawned

        entity.setVelocityBt(event.velocity());
        entity.setInstance(instance, event.spawnPos().withView(viewYaw(event.velocity()), viewPitch(event.velocity())));
        return entity;
    }

    // --- spawn / velocity ---

    private static Pos spawnPos(Entity shooter, ResolvedFlight cfg) {
        Pos eye = shooter.getPosition().add(0, shooter.getEyeHeight(), 0);
        Vec aim = eye.direction();
        // Lateral offset, perpendicular to the look (yaw only) - vanilla 1.8 throwing-hand shift:
        // locX -= cos(yaw)*0.16, locZ -= sin(yaw)*0.16. (26.1 has none.)
        double yaw = Math.toRadians(eye.yaw());
        double lat = cfg.spawnOffsetLateral();
        double lx = -Math.cos(yaw) * lat;
        double lz = -Math.sin(yaw) * lat;
        return eye.add(aim.x() * cfg.spawnOffsetH() + lx, cfg.spawnOffsetV(), aim.z() * cfg.spawnOffsetH() + lz);
    }

    private static Vec launchVelocity(Entity shooter, ResolvedFlight cfg, double power) {
        Vec aim = shooter.getPosition().direction();
        Vec vel = aim.mul(cfg.speed() * power);
        if (cfg.spread() > 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double len = vel.length();
            if (len > 0) {
                vel = vel.div(len)
                        .add(r.nextGaussian() * cfg.spread(), r.nextGaussian() * cfg.spread(), r.nextGaussian() * cfg.spread())
                        .mul(len);
            }
        }
        if (cfg.inheritMomentum()) {
            // 26.1 (Projectile.shootFromRotation) folds the shooter's REAL movement - getKnownMovement(), NOT the
            // server velocity, which is ~0/stale for a client-driven player. positionDelta is the tracked b/t
            // move-delta (players: move packets; non-players: server velocity). 1.8 adds NO momentum at all.
            Vec pv = MotionTracker.positionDelta(shooter);
            double vy = shooter.isOnGround() ? 0 : pv.y(); // horizontal always; vertical only when airborne
            vel = vel.add(pv.x(), vy, pv.z());
        }
        return vel;
    }

    private static float viewYaw(Vec v) { return (float) Math.toDegrees(Math.atan2(v.x(), v.z())); }
    private static float viewPitch(Vec v) {
        double hl = Math.sqrt(v.x() * v.x() + v.z() * v.z());
        return (float) Math.toDegrees(Math.atan2(v.y(), hl));
    }

    // --- registry ---

    /** Registers a type (data only; not enabled). No-op if its key is already registered. */
    public ProjectileSystem register(ProjectileType type) { types.putIfAbsent(type.key(), type); return this; }

    public @Nullable ProjectileType get(Key key) { return types.get(key); }
    public boolean contains(Key key) { return types.containsKey(key); }
    public boolean isEnabled(Key key) { return enabled.contains(key); }

    /** Enables a registered type (wires its launch trigger). Idempotent. */
    public void enable(Key key) {
        ProjectileType type = types.get(key);
        if (type != null && enabled.add(key)) type.enable(this, mm);
    }

    /** Disables an enabled type (tears down its trigger). Idempotent. */
    public void disable(Key key) {
        ProjectileType type = types.get(key);
        if (type != null && enabled.remove(key)) type.disable();
    }

    /** Registers the built-in vanilla projectile types (data only; not enabled). */
    public ProjectileSystem registerVanillaDefaults() {
        register(io.github.term4.minestommechanics.mechanics.projectile.types.Snowball.INSTANCE);
        register(io.github.term4.minestommechanics.mechanics.projectile.types.Egg.INSTANCE);
        register(io.github.term4.minestommechanics.mechanics.projectile.types.Pearl.INSTANCE);
        register(io.github.term4.minestommechanics.mechanics.projectile.types.Arrow.INSTANCE);
        return this;
    }

    /**
     * Installs the projectile system. The config decides what runs: every registered type with a
     * {@link ProjectileConfig#typeConfigs} entry is enabled (its item trigger wires up - same model as
     * {@code DamageSystem.install}). {@code extraTypes} registers and enables custom types built outside the
     * config. Per-shooter scoping resolves through {@code MechanicsProfiles}.
     */
    public static ProjectileSystem install(MinestomMechanics mm, ProjectileConfig cfg, ProjectileType... extraTypes) {
        ProjectileSystem system = new ProjectileSystem(mm, cfg);
        mm.registerProjectiles(system);
        system.registerVanillaDefaults();
        for (ProjectileType type : extraTypes) system.register(type);
        mm.install(system.node);
        for (Key key : cfg.typeConfigs.keySet()) system.enable(key);
        for (ProjectileType type : extraTypes) system.enable(type.key());
        return system;
    }
}
