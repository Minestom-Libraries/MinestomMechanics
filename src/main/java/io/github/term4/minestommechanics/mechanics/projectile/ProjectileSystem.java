package io.github.term4.minestommechanics.mechanics.projectile;

import io.github.term4.minestommechanics.MechanicsKeys;
import io.github.term4.minestommechanics.MechanicsModule;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.api.event.ProjectileLaunchEvent;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ProjectileContext;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ResolvedFlight;
import io.github.term4.minestommechanics.mechanics.projectile.entities.arrow.ArrowEntity;
import io.github.term4.minestommechanics.mechanics.projectile.entities.FireballEntity;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ManagedProjectile;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.minestommechanics.mechanics.projectile.shootables.Shootable;
import io.github.term4.minestommechanics.mechanics.projectile.types.Arrow;
import io.github.term4.minestommechanics.mechanics.projectile.types.Egg;
import io.github.term4.minestommechanics.mechanics.projectile.types.Fireball;
import io.github.term4.minestommechanics.mechanics.projectile.types.Pearl;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileType;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import io.github.term4.minestommechanics.mechanics.projectile.types.Snowball;
import io.github.term4.minestommechanics.mechanics.projectile.types.SplashPotion;
import io.github.term4.minestommechanics.tracking.motion.MotionTracker;
import io.github.term4.minestommechanics.mechanics.attribute.catalog.enchant.Flame;
import io.github.term4.minestommechanics.mechanics.attribute.catalog.enchant.Power;
import io.github.term4.minestommechanics.mechanics.attribute.catalog.enchant.Punch;
import io.github.term4.minestommechanics.util.Directions;
import io.github.term4.minestommechanics.item.Enchants;
import io.github.term4.minestommechanics.util.tick.TickScaler;
import net.kyori.adventure.key.Key;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Projectile system: resolves a {@link ProjectileSnapshot} into a spawn + velocity, fires {@link ProjectileLaunchEvent},
 * and spawns the entity. Mirrors {@code DamageSystem} - types with a {@code typeConfigs} entry enable at install (the
 * per-type {@code enabled} knob gates per launch). Self-driven types wire their item triggers in {@link ProjectileType#enable}.
 */
public final class ProjectileSystem implements MechanicsModule {

    /** This system's identity for per-module TPS scaling (its {@code referenceTps} feel-baseline). */
    public static final Key KEY = Key.key("mm:projectile");

    private final ProjectileConfig config;
    private final Services services;
    private final MinestomMechanics mm;
    private final EventNode<@NotNull Event> node;
    private final Map<Key, ProjectileType> types = new ConcurrentHashMap<>();
    private final Set<Key> enabled = ConcurrentHashMap.newKeySet();

    public ProjectileSystem(MinestomMechanics mm, ProjectileConfig config) {
        this.mm = mm;
        this.config = config;
        this.services = mm.services();
        this.node = EventNode.all("mm:projectile");
        // melee/left-click deflection: attacking a deflectable projectile (a fireball) redirects it along the attacker's look
        node.addListener(EntityAttackEvent.class, e -> {
            if (e.getTarget() instanceof ProjectileEntity target) target.deflectBy(e.getEntity());
        });
    }

    public ProjectileConfig config() { return config; }
    public EventNode<@NotNull Event> node() { return node; }

    /** Effective config for a snapshot carrying none: the shooter's scoped profile, else the install config. */
    private ProjectileConfig configFor(@Nullable Entity shooter) {
        ProjectileConfig scoped = mm.profiles().resolve(shooter, MechanicsKeys.PROJECTILES);
        return scoped != null ? scoped : config;
    }

    /**
     * Launches a projectile from a snapshot: resolves config, computes spawn + velocity (snapshot overrides win, else
     * aim x speed x power + spread + momentum), fires the cancellable {@link ProjectileLaunchEvent}, then spawns the
     * entity. {@code null} if the type is disabled, cancelled, or the shooter has no instance.
     */
    public @Nullable ProjectileEntity launch(ProjectileSnapshot snap) {
        ProjectileSnapshot working = snap.config() != null ? snap : snap.withConfig(configFor(snap.shooter()));
        ProjectileContext ctx = ProjectileContext.of(working, services);
        ProjectileTypeConfig effectiveConfig = ctx.typeConfig();
        ResolvedFlight flight = ProjectileConfigResolver.resolveFlight(effectiveConfig, ctx);
        if (!flight.enabled()) return null;
        return launch(working, effectiveConfig, flight);
    }

    /** The launch-time flight values for {@code snap} under its effective config chain (what {@link #launch} will use); lets a launcher read a knob (e.g. the bow's {@code critChance}) without re-resolving by hand. */
    public ResolvedFlight resolveFlight(ProjectileSnapshot snap) {
        ProjectileSnapshot working = snap.config() != null ? snap : snap.withConfig(configFor(snap.shooter()));
        ProjectileContext ctx = ProjectileContext.of(working, services);
        return ProjectileConfigResolver.resolveFlight(ctx.typeConfig(), ctx);
    }

    private @Nullable ProjectileEntity launch(ProjectileSnapshot working, ProjectileTypeConfig effectiveConfig, ResolvedFlight flight) {
        Entity shooter = working.shooter();
        Instance instance = shooter.getInstance();
        if (instance == null) return null;

        Pos spawnPos = working.spawnPos() != null ? working.spawnPos() : spawnPos(shooter, flight);
        Vec velocity = working.velocity() != null ? working.velocity() : launchVelocity(shooter, flight, working.power());

        ProjectileEntity entity = working.type().createEntity(shooter, working, effectiveConfig);
        stampFlight(entity, flight, working);

        ProjectileLaunchEvent event = new ProjectileLaunchEvent(working, entity, flight, spawnPos, velocity);
        EventDispatcher.call(event);
        if (event.isCancelled()) return null;
        if (event.behavior() != null && entity instanceof ManagedProjectile mp) mp.setBehavior(event.behavior());

        // 1.8 lockstep ONLY for client-PREDICTED projectiles (no per-tick velocity sync): spawn on the wire 1/32 grid so the
        // client's predicted flight matches the server. Server-authoritative ones (fireball: velocitySyncInterval>0, synced
        // every tick) keep FULL precision - else the 1/32 truncation offsets a straight-down explosion from the thrower's
        // actual x/z, leaking horizontal knockback at non-integer coordinates (the explosion is then off-grid from you).
        Vec spawnVel = event.velocity();
        Pos spawnAt = event.spawnPos().withView(Directions.yaw(spawnVel), Directions.pitch(spawnVel));
        if (flight.velocitySyncInterval() <= 0) spawnAt = quantizeToWireGrid(spawnAt);
        entity.setVelocityBt(spawnVel);
        entity.setSpawnPosition(spawnAt);
        entity.setInstance(instance, spawnAt);
        return entity;
    }

    /** Truncates a spawn position to the 1.8 wire grid (1/32) so the server simulates from where a 1.8 client predicts. See {@link #launch}. */
    private static Pos quantizeToWireGrid(Pos p) {
        return new Pos((int) (p.x() * 32.0) / 32.0, (int) (p.y() * 32.0) / 32.0, (int) (p.z() * 32.0) / 32.0, p.yaw(), p.pitch());
    }

    /** Stamps the resolved flight config onto a freshly created entity (physics, sync, immunity, behavior, pickup). */
    private static void stampFlight(ProjectileEntity entity, ResolvedFlight flight, ProjectileSnapshot snap) {
        entity.setBoundingBox(flight.boundingBox().width(), flight.boundingBox().height(), flight.boundingBox().depth());
        entity.setAerodynamics(new Aerodynamics(flight.gravity(), flight.verticalDrag(), flight.horizontalDrag()));
        entity.setBroadcastMovement(flight.broadcastMovement());
        entity.setSynchronizationTicks(TickScaler.duration(flight.syncInterval(), KEY));
        entity.setVelocitySyncInterval(TickScaler.duration(flight.velocitySyncInterval(), KEY));
        // Minestom seeds nextSynchronizationTick from the DEFAULT interval (20) and setSynchronizationTicks doesn't reset it,
        // so a denser cadence stays silent until tick 20 (the fireball froze for ~1s, then snapped downrange). Kick the first
        // sync now for per-tick-velocity projectiles so the client sees them move from spawn (vanilla/Hypixel parity).
        if (flight.velocitySyncInterval() > 0) entity.synchronizeNextTick();
        entity.setShooterImmunityTicks(TickScaler.duration(flight.shooterImmunityTicks(), KEY));
        entity.setEntityHitGrow(flight.entityHitGrow());
        entity.setPhysicsOrder(flight.physicsOrder());
        entity.setLeftOwnerImmunity(flight.leftOwnerImmunity());
        entity.setStickPullback(flight.stickPullback());
        if (entity instanceof ManagedProjectile mp) {
            mp.setBehavior(snap.behavior() != null ? snap.behavior() : flight.behavior());
        }
        if (entity instanceof ArrowEntity arrow) {
            arrow.setShakeTicks(flight.shakeTicks());
            if (flight.pickupBox() != null) arrow.setPickupBox(flight.pickupBox());
        }
        if (entity instanceof FireballEntity fireball) fireball.setExplosionPower((float) flight.explosionPower());
        // read off the launch item for ANY projectile - a preset may put Power/Punch/Flame on non-bows
        entity.setProjectileEnchants(Enchants.level(snap.item(), Power.KEY), Enchants.level(snap.item(), Punch.KEY), Enchants.level(snap.item(), Flame.KEY));
    }

    // Projectile spawn

    private static Pos spawnPos(Entity shooter, ResolvedFlight cfg) {
        Pos eye = shooter.getPosition().add(0, shooter.getEyeHeight(), 0);
        Vec aim = eye.direction();
        Vec right = Directions.rightOf(eye.yaw());
        double side = cfg.spawnOffsetSideways();
        return eye.add(aim.x() * cfg.spawnOffsetForward() + right.x() * side, cfg.spawnOffsetVertical(),
                aim.z() * cfg.spawnOffsetForward() + right.z() * side);
    }

    private Vec launchVelocity(Entity shooter, ResolvedFlight cfg, double power) {
        Pos view = shooter.getPosition();
        Vec aim = cfg.launchPitchOffset() != 0
                ? Directions.headingWithPitchOffset(view.yaw(), view.pitch(), cfg.launchPitchOffset())
                : view.direction();
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
        double mh = cfg.momentumHorizontal(), mv = cfg.momentumVertical();
        if (mh != 0 || mv != 0) {
            // fold a fraction of the shooter's velocity (26.1 shootFromRotation)
            Vec sv = MotionTracker.positionDelta(shooter);
            vel = vel.add(sv.x() * mh, sv.y() * mv, sv.z() * mh);
        }
        return vel;
    }

    // Registry

    /** Registers a type (data only; not enabled). No-op if its key is already registered. */
    public ProjectileSystem register(ProjectileType type) { types.putIfAbsent(type.key(), type); return this; }

    public @Nullable ProjectileType get(Key key) { return types.get(key); }
    public boolean contains(Key key) { return types.containsKey(key); }
    public boolean isEnabled(Key key) { return enabled.contains(key); }

    /** Enables a registered type (wires its launch trigger). Idempotent; throws for an unregistered key (catches preset typos). */
    public void enable(Key key) {
        ProjectileType type = types.get(key);
        if (type == null) throw new IllegalArgumentException("No projectile type registered for " + key.asString());
        if (enabled.add(key)) type.enable(this, mm);
    }

    /** Disables an enabled type (tears down its trigger). Idempotent. */
    public void disable(Key key) {
        ProjectileType type = types.get(key);
        if (type != null && enabled.remove(key)) type.disable();
    }

    /** Registers the built-in vanilla projectile types (data only; not enabled). */
    public ProjectileSystem registerVanillaDefaults() {
        register(Snowball.INSTANCE);
        register(Egg.INSTANCE);
        register(Pearl.INSTANCE);
        register(Arrow.INSTANCE);
        register(Fireball.INSTANCE);
        register(SplashPotion.INSTANCE);
        return this;
    }

    /**
     * Installs reading the GLOBAL profile's {@link ProjectileConfig}: its {@code typeConfigs} enable the self-launching
     * types and its {@code shootables} (item launchers, e.g. {@code new Bow()}) are mounted. Set the profile before
     * installing. Per-hit physics still resolves per-scope; only these one-time registrations are read here.
     */
    public static ProjectileSystem install(MinestomMechanics mm) {
        ProjectileConfig global = mm.profiles().resolve(null, MechanicsKeys.PROJECTILES);
        return install(mm, global != null ? global : ProjectileConfig.builder().build());
    }

    /** Installs from an explicit config (the modular path): enables its {@code typeConfigs} and mounts its {@code shootables}. */
    public static ProjectileSystem install(MinestomMechanics mm, ProjectileConfig cfg) {
        ProjectileSystem system = new ProjectileSystem(mm, cfg);
        mm.register(system);
        system.registerVanillaDefaults();
        mm.install(system.node);
        for (Key key : cfg.typeConfigs.keySet()) system.enable(key);
        for (Shootable shootable : cfg.shootables()) shootable.install(system.node, system);
        return system;
    }
}
