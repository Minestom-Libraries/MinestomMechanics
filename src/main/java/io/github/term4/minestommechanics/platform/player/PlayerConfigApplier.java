package io.github.term4.minestommechanics.platform.player;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.platform.compatibility.CompatConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the scoped player-platform configs - {@link PlayerConfig} and {@link CompatConfig} - at spawn
 * (join and instance change) and on every profile assignment change ({@code MechanicsProfiles} set calls
 * re-apply to all online players), so swaps are live without any polling. Each member is applied only when a
 * scope sets it; otherwise the player is left untouched, so the manual {@link OptimizedPlayer} setters
 * ({@code setPositionBroadcastInterval} / {@code setDisabledPoses}) stay authoritative.
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

    /** Applies the player's scoped platform configs; each member is a no-op when no scope sets it. */
    public static void apply(MinestomMechanics mm, Player player) {
        if (!(player instanceof OptimizedPlayer op)) return;
        PlayerConfig cfg = mm.profiles().playerFor(player);
        if (cfg != null && cfg.positionBroadcastInterval != null) {
            op.setPositionBroadcastInterval(Math.max(1, cfg.positionBroadcastInterval));
        }
        CompatConfig compat = mm.profiles().compatFor(player);
        if (compat != null) {
            if (compat.disabledPoses != null) op.setDisabledPoses(compat.disabledPoses);
            if (compat.restrictMovement != null) op.setRestrictMovement(compat.restrictMovement);
            if (compat.legacyHitbox != null) op.setLegacyHitbox(compat.legacyHitbox);
        }
    }
}
