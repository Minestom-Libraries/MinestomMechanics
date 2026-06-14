package io.github.term4.minestommechanics.tracking;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.EnvironmentalTickProducer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

// TODO: Create ONE ticker, and attach sub tickers (environment, other stuff, everything) to that main ticker.
/**
 * Single {@link EntityTickEvent} listener for self-driven environmental producers (cactus, burning).
 * Mounted under {@code mm:trackers} when the damage system first binds; producers register/unregister
 * as their types are enabled/disabled.
 */
public final class EnvironmentalDamageTicker implements Tracker {

    private static final EnvironmentalDamageTicker INSTANCE = new EnvironmentalDamageTicker();

    private final EventNode<@NotNull Event> node = EventNode.all("mm:env-damage-ticker");
    private final Set<EnvironmentalTickProducer> producers = new CopyOnWriteArraySet<>();
    private boolean mounted;
    private @Nullable DamageSystem system;

    private EnvironmentalDamageTicker() {
        node.addListener(EntityTickEvent.class, this::onTick);
    }

    public static EnvironmentalDamageTicker instance() { return INSTANCE; }

    /** Binds the active damage system and ensures this tracker is mounted once. */
    public synchronized void bind(DamageSystem system, MinestomMechanics mm) {
        this.system = system;
        if (!mounted) {
            mm.mountTracker(this);
            mounted = true;
        }
    }

    public void register(EnvironmentalTickProducer producer) {
        producers.add(producer);
    }

    public void unregister(EnvironmentalTickProducer producer) {
        producers.remove(producer);
    }

    @Override
    public EventNode<@NotNull Event> node() { return node; }

    private void onTick(EntityTickEvent event) {
        DamageSystem sys = this.system;
        if (sys == null || producers.isEmpty()) return;
        for (EnvironmentalTickProducer producer : producers) {
            producer.tick(event, sys);
        }
    }
}
