package io.github.term4.minestommechanics.mechanics.attack;

import io.github.term4.minestommechanics.MechanicsProfiles;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.api.event.AttackEvent;
import io.github.term4.minestommechanics.mechanics.attack.hitdetection.PacketHit;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;

/**
 * Attack pipeline: detects hits, fires the {@link AttackEvent} API, and runs the configured ruleset.
 * Has no invulnerability window or hit buffering of its own - every detected hit is processed, and the
 * damage / knockback systems gate themselves on their own windows (vanilla: {@code EntityHuman.attack}
 * always runs; {@code damageEntity} decides what lands). Preset-specific behaviors (e.g. Minemen's hit
 * queue, future swing-hit detection) live in custom rulesets.
 */
public final class AttackSystem {

    private final AttackConfig config;
    private final MechanicsProfiles profiles;
    private final EventNode<@NotNull Event> apiEvents;
    private final EventNode<@NotNull Event> node;

    public AttackSystem(MinestomMechanics mm, AttackConfig config) {
        this.config = config;
        this.profiles = mm.profiles();
        this.apiEvents = mm.events();
        this.node = EventNode.all("mm:attack");

        var services = mm.services();
        // Detection is always installed; `enabled` gates per hit through the config chain, so a world that
        // boots with a disabled install config can be switched live by assigning an enabled profile.
        PacketHit.install(node, null, snap -> handleAttack(snap, services));
    }

    private void handleAttack(AttackSnapshot snap, io.github.term4.minestommechanics.Services services) {
        // Config chain: snapshot override -> attacker's scoped profile -> install config.
        if (snap.config() == null) {
            AttackConfig scoped = profiles.attackFor(snap.attacker());
            snap = snap.withConfig(scoped != null ? scoped : config);
        }

        AttackEvent api = new AttackEvent(snap, services);
        apiEvents.call(api);
        // enabled is read off the live resolved config, so listeners may still swap in an enabled config
        // (event.config(...)) to let a specific hit through a disabled scope.
        if (api.isCancelled() || !api.process() || !api.resolvedConfig().enabled()) return;

        AttackEvent.AttackRule proc = api.processor() != null
                ? api.processor().create(services)
                : api.resolvedConfig().ruleset().create(services);
        proc.processAttack(api);
    }

    public static AttackSystem install(MinestomMechanics mm, AttackConfig config) {
        var system = new AttackSystem(mm, config);
        mm.registerAttack(system);
        mm.install(system.node);
        return system;
    }

    public AttackConfig config() { return config; }
    public EventNode<@NotNull Event> node() { return node; }
}
