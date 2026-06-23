package io.github.term4.minestommechanics.platform.compatibility;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import org.jetbrains.annotations.NotNull;

/**
 * Server-authoritative half of pose disabling ({@code CompatConfig.restrictMovement}): rejects a move that newly puts the
 * player's (pose-aware {@link Player#getBoundingBox()}) server hitbox into a solid block it wasn't already overlapping - so
 * a modern client rendering itself crawling/swimming can't traverse a gap its hitbox can't fit, while a player already
 * stuck in a block can still slide out. With {@code legacyHitbox} on the box is the 1.8 standing box, so the 1.5-block sneak
 * gap is restricted too. Installed once when the player provider is on; inert unless the player's config enables it. Minemen behaviour.
 */
public final class CompatMovement {

    private CompatMovement() {}

    /** Installs the move-restriction listener. Inert unless a player's {@code CompatConfig.restrictMovement} is on. */
    public static void install(MinestomMechanics mm) {
        EventNode<@NotNull PlayerEvent> node = EventNode.type("mm:compat-movement", EventFilter.PLAYER);
        node.addListener(PlayerMoveEvent.class, CompatMovement::onMove);
        mm.install(node);
    }

    private static void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!(player instanceof OptimizedPlayer op) || !op.isRestrictMovement()) return;
        // spectators noclip; a passenger's collision is the vehicle's - leave both alone
        if (player.getGameMode() == GameMode.SPECTATOR || player.getVehicle() != null) return;
        Pos from = player.getPosition();
        Pos to = event.getNewPosition();
        if (to.samePoint(from)) return; // look-only change: the hitbox didn't move
        Instance instance = player.getInstance();
        if (instance == null || !entersNewCollision(instance, player.getBoundingBox(), from, to)) return;

        if (to.yaw() != from.yaw() || to.pitch() != from.pitch()) {
            // Rotating move: revert position WITHOUT an absolute-view correction (that drags a predicting client's camera).
            // refreshPosition(sendPackets=false) mutates getPosition() to trip processMovement's "teleported during event"
            // early-out silently, so the only packet the client gets is Minemen's exact one below: flags VIEW (yaw/pitch
            // relative delta-0 = camera kept), absolute position revert, zero delta (momentum killed), id -1 (no gating).
            // NOT setView - on a Player that is a full absolute-view teleport every tick (the cause of the old drag; see memory).
            player.refreshPosition(from.withView(to.yaw(), to.pitch()), false, false);
            player.sendPacket(new PlayerPositionAndLookPacket(-1, from, Vec.ZERO, 0f, 0f, (byte) RelativeFlags.VIEW));
        } else {
            // Non-rotating move: the view is static, so a plain cancel (its snap-back is to the same view) blocks it cleanly.
            event.setCancelled(true);
        }
    }

    /**
     * Whether the move newly puts {@code box} into a solid block it wasn't already overlapping at {@code from}. A block the
     * box already overlaps at {@code from} is ignored (so a player stuck in a block can slide out); only freshly entering a
     * block - crawling in from open ground or along a tunnel - is caught. Normal (non-colliding) movement is never affected.
     */
    private static boolean entersNewCollision(Instance instance, BoundingBox box, Pos from, Pos to) {
        var blocks = box.getBlocks(to);
        while (blocks.hasNext()) {
            var bp = blocks.next();
            Block block;
            try {
                block = instance.getBlock(bp.blockX(), bp.blockY(), bp.blockZ(), Block.Getter.Condition.TYPE);
            } catch (Exception ignored) {
                continue; // unloaded chunk -> no collision
            }
            if (block == null || block.id() == Block.SCAFFOLDING.id()) continue; // scaffolding has a dynamic shape; Minestom skips it
            var shape = block.registry().collisionShape();
            if (!shape.intersectBox(to.sub(bp.blockX(), bp.blockY(), bp.blockZ()), box)) continue;  // not colliding at the destination
            if (shape.intersectBox(from.sub(bp.blockX(), bp.blockY(), bp.blockZ()), box)) continue;  // already inside at the source -> not new (allow sliding out)
            return true;                                                                             // newly entered a solid block
        }
        return false;
    }
}
