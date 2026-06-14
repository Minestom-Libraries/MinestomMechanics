package io.github.term4.minestommechanics.mechanics.knockback;

import io.github.term4.minestommechanics.MechanicsProfiles;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.api.event.KnockbackEvent;
import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.util.LegacyVelocity;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Knockback system: Config can be changed via KnockbackConfig or the KnockbackEvent API. Has no
 * invulnerability window of its own (neither does vanilla - 1.8 gates base KB on {@code damageEntity}'s
 * fresh-hit flag): every {@link #apply} call deals knockback, and gating lives in the attack processor
 * (e.g. {@code Vanilla18.LegacyAttack} applies KB only on {@code HitResult.FULL_HIT}).
 */
public final class KnockbackSystem {

    private final KnockbackConfig config;
    private final KnockbackCalculator calc;
    private final MechanicsProfiles profiles;
    private final EventNode<@NotNull Event> node;

    public KnockbackSystem(MinestomMechanics mm, KnockbackConfig config) {
        this.config = config;
        this.profiles = mm.profiles();
        this.node = EventNode.all("mm:knockback");
        Services services = mm.services();
        KnockbackConfig defaults = Vanilla18.kb();
        this.calc = new KnockbackCalculator(services, defaults);
    }

    /** Effective config for a snapshot carrying none: the victim's scoped profile, else the install config. */
    private KnockbackConfig configFor(@Nullable Entity target) {
        KnockbackConfig scoped = profiles.knockbackFor(target);
        return scoped != null ? scoped : config;
    }

    public void apply(KnockbackSnapshot snap) {
        // KnockbackEvent API (the resolver hook backs event.resolvedConfig(): it previews the exact values
        // compute() will use for whatever finalSnap the listeners settle on)
        var event = new KnockbackEvent(snap,
                s -> calc.resolveConfig(s.config() != null ? s : s.withConfig(configFor(s.target()))));
        EventDispatcher.call(event);
        if (event.isCancelled()) return;
        KnockbackSnapshot finalSnap = event.finalSnap();

        // Knockback requires a target
        if (finalSnap.target() == null) return;

        // Build knockback velocity vector
        @Nullable Vec velocity;

        // If the API explicitly set the velocity, use that
        if (event.velocity() != null) { velocity = event.velocity(); }

        // If no velocity was provided, calculate using the snapshot (config chain: snapshot override ->
        // victim's scoped profile -> install config)
        else {
            velocity = finalSnap.config() != null
                    ? calc.compute(finalSnap)
                    : calc.compute(finalSnap.withConfig(configFor(finalSnap.target())));

            // If the API explicitly set the direction, use the calculated velocity magnitude & the API's direction
            if (velocity != null && event.direction() != null) {
                double mag = Math.sqrt(velocity.x() * velocity.x() + velocity.z() * velocity.z());
                Vec dir = event.direction().normalize();
                velocity = new Vec(dir.x() * mag, velocity.y(), dir.z() * mag);
            }
        }

        // Apply knockback to target. Vanilla broadcasts the KB vector to the client but RESTORES the victim's
        // pre-knockback server velocity (EntityHuman.attack saves motX/Y/Z, lets a() overwrite + broadcast, then
        // restores), so the knockback is NOT folded into the next hit - the tracker is deliberately not told about it.
        // quantizeVelocity (default on) sends what a 1.8 server's wire encoding would have produced.
        // TODO(perf/structure): the config is resolved twice per hit (calc.compute resolves internally, and
        //  again here for quantizeVelocity). Fold into one resolution when the event layer is restructured
        //  (planned: PreDamageEvent / DamageModifyEvent / FinalDamageEvent), e.g. compute() consuming a
        //  pre-resolved config carried by the event.
        if (velocity != null) {
            Entity target = finalSnap.target();
            KnockbackSnapshot cfgSnap = finalSnap.config() != null ? finalSnap : finalSnap.withConfig(configFor(target));
            boolean quantize = !Boolean.FALSE.equals(calc.resolveConfig(cfgSnap).quantizeVelocity());
            target.setVelocity(quantize ? LegacyVelocity.snap(velocity) : velocity);
        }
    }

    public KnockbackConfig config() { return config; }

    /** This system's listener node ({@code mm:knockback}). Empty today - future hooks mount here. */
    public EventNode<@NotNull Event> node() { return node; }

    public static KnockbackSystem install(MinestomMechanics mm, KnockbackConfig config) {
        var system = new KnockbackSystem(mm, config);
        mm.registerKnockback(system);
        mm.install(system.node);
        return system;
    }

}