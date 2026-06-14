package io.github.term4.minestommechanics.platform.player;

import io.github.term4.minestommechanics.MinestomMechanics;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the scoped {@link PlayerConfig} at spawn (join and instance change) and on every profile
 * assignment change ({@code MechanicsProfiles} set calls re-apply to all online players), so swaps are
 * live without any polling. Players with no config in scope (player / instance / global profile) are
 * left untouched, so the manual {@link OptimizedPlayer#setPositionBroadcastInterval} API stays
 * authoritative.
 */
public final class PlayerConfigApplier {

    private PlayerConfigApplier() {}

    /** Installs the spawn listener. Called by {@code MinestomMechanics.init()} when installPlayerProvider is on. */
    public static void install(MinestomMechanics mm) {
        EventNode<@NotNull PlayerEvent> node = EventNode.type("mm:player-config", EventFilter.PLAYER);
        node.addListener(PlayerSpawnEvent.class, e -> apply(mm, e.getPlayer()));
        mm.install(node);
    }

    /** Applies the scoped config to every online player (run when profile assignments change). */
    public static void applyAll(MinestomMechanics mm) {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) apply(mm, p);
    }

    /** Applies the player's scoped config; no-op when no scope sets one. */
    public static void apply(MinestomMechanics mm, Player player) {
        if (!(player instanceof OptimizedPlayer op)) return;
        PlayerConfig cfg = mm.profiles().playerFor(player);
        if (cfg == null) return;
        if (cfg.positionBroadcastInterval != null) {
            op.setPositionBroadcastInterval(Math.max(1, cfg.positionBroadcastInterval));
        }
    }
}
