package io.github.term4.minestommechanics.mechanics.attack;

import io.github.term4.minestommechanics.MechanicsProfiles;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.api.event.AttackEvent;
import io.github.term4.minestommechanics.api.event.PreAttackEvent;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;

/**
 * Attack pipeline: {@link HitDetection} turns raw input into snapshots, then the system fires the {@link AttackEvent}
 * API and runs the configured ruleset. No invul window or hit buffering of its own - every hit is processed and the
 * damage/knockback systems gate themselves. Preset behaviors (e.g. Mmc18's hit queue) live in custom rulesets/detections.
 */
public final class AttackSystem {

    private final AttackConfig config;
    private final MechanicsProfiles profiles;
    private final Services services;
    private final EventNode<@NotNull Event> node;

    /**
     * @param detection how hits are detected; none given installs {@link HitDetection#PACKET}. Always live - {@code enabled}
     *                  gates per hit, so a disabled install config can be switched live by assigning an enabled profile.
     */
    public AttackSystem(MinestomMechanics mm, AttackConfig config, HitDetection... detection) {
        this.config = config;
        this.profiles = mm.profiles();
        this.services = mm.services();
        this.node = EventNode.all("mm:attack");

        if (detection.length == 0) detection = new HitDetection[]{HitDetection.PACKET};
        for (HitDetection d : detection) d.install(node, this::apply);
    }

    /**
     * Feeds a detected hit through the pipeline: config chain (snapshot -> attacker scope -> install) -> {@link AttackEvent}
     * -> ruleset. Public so custom hit detection submits hits exactly like the built-in packet detection.
     */
    public void apply(AttackSnapshot snap) {
        // earliest hook: raw detected hit, before any config/ruleset. Observe-only today (reach log); a future anticheat can veto here.
        PreAttackEvent pre = new PreAttackEvent(snap.attacker(), snap.target(), services);
        EventDispatcher.call(pre);
        if (pre.isCancelled()) return;
        // config chain: snapshot -> attacker scope -> install. inert when all three are absent; an empty config processes at the vanilla floor
        AttackConfig effective = snap.config();
        if (effective == null) {
            AttackConfig scoped = profiles.attackFor(snap.attacker());
            effective = scoped != null ? scoped : config;
        }
        if (effective == null) return;
        if (snap.config() == null) snap = snap.withConfig(effective);

        AttackEvent api = new AttackEvent(snap, services);
        EventDispatcher.call(api);
        // enabled read off the live resolved config, so a listener can swap in an enabled config to let a hit through
        if (api.isCancelled() || !api.process() || !api.resolvedConfig().enabled()) return;

        AttackEvent.AttackRule proc = api.processor() != null
                ? api.processor().create(services)
                : api.resolvedConfig().ruleset().create(services);
        proc.processAttack(api);
    }

    /** Installs inert (no install-level config): a detected hit with no scoped or snapshot config is dropped. Pass an empty config to process at the vanilla floor. */
    public static AttackSystem install(MinestomMechanics mm, HitDetection... detection) {
        return install(mm, (AttackConfig) null, detection);
    }

    /** Installs the system. {@code detection} as in {@link #AttackSystem(MinestomMechanics, AttackConfig, HitDetection...)}. */
    public static AttackSystem install(MinestomMechanics mm, AttackConfig config, HitDetection... detection) {
        var system = new AttackSystem(mm, config, detection);
        mm.registerAttack(system);
        mm.install(system.node);
        return system;
    }

    public AttackConfig config() { return config; }

    /** This system's listener node ({@code mm:attack}); everything the system hooks lives under it. */
    public EventNode<@NotNull Event> node() { return node; }
}
