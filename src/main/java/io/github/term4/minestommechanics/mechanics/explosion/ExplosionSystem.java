package io.github.term4.minestommechanics.mechanics.explosion;

import io.github.term4.minestommechanics.MechanicsKeys;
import io.github.term4.minestommechanics.MechanicsModule;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.api.event.ExplosionEvent;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.explosion.ExplosionDamage;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionConfigResolver.ExplosionContext;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionConfigResolver.ResolvedExplosionConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSnapshot;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import net.kyori.adventure.key.Key;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.Explosion;
import net.minestom.server.instance.ExplosionSupplier;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.server.utils.WeightedList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explosion system: entity selection + exposure raytrace + the vanilla falloff curve, fired through {@link ExplosionEvent}.
 * Knockback rides the {@link KnockbackSystem} (base + push) on a fresh hit, the explosion packet during i-frames; damage
 * rides the {@link DamageSystem}; blocks are delegated to a listener off {@link ExplosionEvent#center()} + power.
 */
public final class ExplosionSystem implements MechanicsModule {

    public static final Key KEY = Key.key("mm:explosion");

    /** Radius used when neither the call nor the config supplies one (vanilla TNT). */
    public static final float DEFAULT_POWER = 4.0f;

    private final @Nullable ExplosionConfig config;
    private final Services services;
    private final EventNode<@NotNull Event> node;

    public ExplosionSystem(MinestomMechanics mm, @Nullable ExplosionConfig config) {
        this.config = config;
        this.services = mm.services();
        this.node = EventNode.all("mm:explosion");
    }

    /** Per-player explosion: every player in the instance gets the visual, those in range their own falloff knockback. */
    public void explode(@NotNull Instance instance, @NotNull Point center, float power) {
        explode(instance, center, power, null);
    }

    public void explode(@NotNull Instance instance, @NotNull Point center, float power, @Nullable Entity source) {
        detonate(instance, center, power, source, resolve(instance, center, source));
    }

    /** Per-player explosion using the configured (or default) radius. */
    public void explode(@NotNull Instance instance, @NotNull Point center, @Nullable Entity source) {
        ResolvedExplosionConfig resolved = resolve(instance, center, source);
        float power = resolved.power() != null ? resolved.power().floatValue() : DEFAULT_POWER;
        detonate(instance, center, power, source, resolved);
    }

    private void detonate(Instance instance, Point center, float power, @Nullable Entity source, ResolvedExplosionConfig resolved) {
        List<ExplosionEvent.Target> targets = computeAndFire(instance, center, power, source, resolved);
        if (targets != null) applyPerPlayer(instance, center, power, source, targets);
    }

    /**
     * One grouped explosion (single packet to everyone): cheaper, but the shared {@code knockback} is applied uniformly
     * by every client (pass {@code null} for none). Damage is still per-entity. Use {@link #explode} for falloff knockback.
     */
    public void explodeUniform(@NotNull Instance instance, @NotNull Point center, float power,
                               @Nullable Entity source, @Nullable Vec knockback) {
        ResolvedExplosionConfig resolved = resolve(instance, center, source);
        List<ExplosionEvent.Target> targets = computeAndFire(instance, center, power, source, resolved);
        if (targets == null) return;
        applyDamage(source, center, targets);
        PacketSendingUtils.sendGroupedPacket(instance.getPlayers(), packet(center, power, knockback));
    }

    /** Config: the source's scope chain (player -&gt; instance -&gt; global) over the install config. */
    private ResolvedExplosionConfig resolve(Instance instance, Point center, @Nullable Entity source) {
        ExplosionConfig scoped = services.profiles().resolve(source, MechanicsKeys.EXPLOSION);
        return ExplosionConfigResolver.resolve(scoped != null ? scoped : config, ExplosionContext.of(instance, center, source, services));
    }

    /** Builds the per-entity result set, fires the event, and returns the (possibly listener-edited) targets, or {@code null} if cancelled. */
    private @Nullable List<ExplosionEvent.Target> computeAndFire(Instance instance, Point center, float power,
                                                                 @Nullable Entity source, ResolvedExplosionConfig resolved) {
        List<ExplosionEvent.Target> targets = computeTargets(instance, center, power, source, resolved);
        ExplosionEvent event = new ExplosionEvent(instance, center, power, source, resolved.fire(), targets);
        EventDispatcher.call(event);
        return event.isCancelled() ? null : event.targets();
    }

    private List<ExplosionEvent.Target> computeTargets(Instance instance, Point center, float power,
                                                       @Nullable Entity source, ResolvedExplosionConfig resolved) {
        List<ExplosionEvent.Target> targets = new ArrayList<>();
        double doubleRadius = power * 2.0;
        if (doubleRadius <= 0.0) return targets;
        for (Entity entity : instance.getNearbyEntities(center, doubleRadius + 1.0)) {
            if (!(entity instanceof LivingEntity)) continue; // player-scoped (players are LivingEntity); mobs out of scope
            if (entity == source && !resolved.affectsSource()) continue;
            double distance = entity.getPosition().distance(center);
            if (distance > doubleRadius) continue;
            Point eyeOrigin = entity.getPosition().add(0, entity.getEyeHeight(), 0);
            float exposure = resolved.exposure() ? ExplosionExposure.seenPercent(instance, center, entity) : 1.0f;
            // knockback reduction (Blast Protection / KB resistance): TODO via the attribute layer
            ExplosionCalculator.Hit hit = ExplosionCalculator.compute(center, power, eyeOrigin, distance, exposure,
                    resolved.damageConstant(), resolved.floorDamage(), resolved.knockbackMultiplier(), 0.0);
            if (hit == null) continue;
            targets.add(new ExplosionEvent.Target(entity, distance, exposure, hit.knockback(), hit.damage()));
        }
        return targets;
    }

    private void applyPerPlayer(Instance instance, Point center, float power, @Nullable Entity source,
                                List<ExplosionEvent.Target> targets) {
        DamageSystem damage = services.damage();
        KnockbackSystem knockback = services.knockback();
        Map<Player, Vec> packetKnockback = new HashMap<>(); // i-frame push, client-applied from the explosion packet
        for (ExplosionEvent.Target target : targets) {
            Entity entity = target.entity();
            Vec push = target.knockback();
            // damage first (vanilla order); the outcome splits delivery - FRESH: base KB + push via the KnockbackSystem; i-frame: push via the packet
            DamageSystem.DamageOutcome outcome = DamageSystem.DamageOutcome.BLOCKED;
            if (damage != null && entity instanceof LivingEntity && target.damage() > 0f) {
                outcome = damage.apply(new DamageSnapshot(entity, ExplosionDamage.INSTANCE, source, center,
                        null, null, target.damage(), null, null));
            }
            if (!(entity instanceof Player player) || push == null) continue;
            if (outcome == DamageSystem.DamageOutcome.FRESH_DAMAGE && source != null && knockback != null) {
                // base KB from the source (active config, non-melee = no sprint extras) + the push, as one velocity
                knockback.apply(new KnockbackSnapshot(player, false, source, null, null, null), push);
            } else {
                packetKnockback.put(player, push);
            }
        }
        // visual to every player; i-frame targets also carry their push in playerKnockback
        for (Player player : instance.getPlayers()) {
            player.sendPacket(packet(center, power, packetKnockback.get(player)));
        }
    }

    private void applyDamage(@Nullable Entity source, Point center, List<ExplosionEvent.Target> targets) {
        DamageSystem damage = services.damage();
        if (damage == null) return;
        for (ExplosionEvent.Target target : targets) {
            if (target.entity() instanceof LivingEntity && target.damage() > 0f) {
                damage.apply(new DamageSnapshot(target.entity(), ExplosionDamage.INSTANCE, source, center,
                        null, null, target.damage(), null, null));
            }
        }
    }

    private static ExplosionPacket packet(Point center, float power, @Nullable Point knockback) {
        return new ExplosionPacket(center, power, 0, knockback,
                Particle.EXPLOSION, SoundEvent.ENTITY_GENERIC_EXPLODE, WeightedList.of());
    }

    /** An {@link ExplosionSupplier} routing {@code instance.explode(...)} through this system (no source; breaks no blocks). */
    public ExplosionSupplier supplier() {
        return (x, y, z, strength, data) -> new RoutedExplosion(this, x, y, z, strength);
    }

    public EventNode<@NotNull Event> node() { return node; }

    public static ExplosionSystem install(MinestomMechanics mm) {
        return install(mm, mm.profiles().resolve(null, MechanicsKeys.EXPLOSION));
    }

    public static ExplosionSystem install(MinestomMechanics mm, @Nullable ExplosionConfig config) {
        var system = new ExplosionSystem(mm, config);
        mm.register(system);
        mm.install(system.node);
        return system;
    }

    private static final class RoutedExplosion extends Explosion {
        private final ExplosionSystem system;

        RoutedExplosion(ExplosionSystem system, float x, float y, float z, float strength) {
            super(x, y, z, strength);
            this.system = system;
        }

        @Override protected List<Point> prepare(Instance instance) { return List.of(); }

        @Override public void apply(Instance instance) {
            system.explode(instance, new Vec(getCenterX(), getCenterY(), getCenterZ()), getStrength());
        }
    }
}
