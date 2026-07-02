package io.github.term4.minestommechanics.platform.fixes.world;

import io.github.term4.minestommechanics.util.tick.TickPhase;
import io.github.term4.minestommechanics.util.tick.TickSystem;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerCancelDiggingEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerFinishDiggingEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import net.minestom.server.utils.block.BlockBreakCalculation;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcasts block-break progress (the crack overlay) to other players - Minestom never sends
 * {@link BlockBreakAnimationPacket}, so mining is invisible to everyone but the miner. Mirrors vanilla
 * {@code ServerPlayerGameMode}/{@code ServerLevel.destroyBlockProgress}: stage {@code (int)(progress*10)} to every
 * player in the instance within 32 blocks except the miner (who predicts its own cracks), re-sent only on stage
 * change, {@code -1} to clear on finish/abort/block-gone. Progress recomputes from
 * {@link BlockBreakCalculation#breakTicks} each tick, so tool swaps and effects track like vanilla.
 *
 * <p>This is really a vanilla FEATURE Minestom omits (belongs with block-drops / item pickup), not a compat fix - it
 * only lives under {@code fixes} for now. Like vanilla, using an item mid-mine can leave a stuck crack (vanilla dodges
 * this client-side via {@code startUseItem}'s {@code !isDestroying()} gate, which Animatium {@code LEFT_CLICK_ITEM_USAGE}
 * removes); not handled - same as vanilla.
 */
public final class BlockBreakProgressFix {

    /** Vanilla broadcast radius (squared): 32 blocks around the mined position. */
    private static final double BROADCAST_RANGE_SQ = 1024.0;
    private static final byte CLEAR_STAGE = -1;

    private record Dig(Instance instance, BlockVec pos, long startTick, byte lastStage) {}

    private final Map<UUID, Dig> digs = new ConcurrentHashMap<>();

    private BlockBreakProgressFix() {}

    public static void install(EventNode<@NotNull Event> node) {
        var fix = new BlockBreakProgressFix();
        node.addListener(PlayerStartDiggingEvent.class, e -> fix.start(e.getPlayer(), e.getInstance(), e.getBlockPosition()));
        node.addListener(PlayerCancelDiggingEvent.class, e -> fix.clear(e.getPlayer()));
        node.addListener(PlayerFinishDiggingEvent.class, e -> fix.clear(e.getPlayer()));
        node.addListener(PlayerDisconnectEvent.class, e -> fix.clear(e.getPlayer()));
        TickSystem.register(TickPhase.DEFAULT, ctx -> fix.tick(ctx.instance()));
    }

    /** Only fired for non-instant breaks, so every tracked dig needs the crack overlay. */
    private void start(Player miner, Instance instance, BlockVec pos) {
        Dig previous = digs.get(miner.getUuid());
        if (previous != null && !previous.pos().equals(pos)) broadcast(miner, previous, CLEAR_STAGE);
        Dig dig = new Dig(instance, pos, TickSystem.instanceTick(instance), CLEAR_STAGE);
        digs.put(miner.getUuid(), update(miner, dig));
    }

    private void clear(Player miner) {
        Dig dig = digs.remove(miner.getUuid());
        if (dig != null) broadcast(miner, dig, CLEAR_STAGE);
    }

    private void tick(Instance instance) {
        for (Map.Entry<UUID, Dig> entry : digs.entrySet()) {
            Dig dig = entry.getValue();
            if (dig.instance() != instance) continue;
            Player miner = instance.getPlayerByUuid(entry.getKey());
            if (miner == null || instance.getBlock(dig.pos()).isAir()) { // left the instance or the block is gone
                digs.remove(entry.getKey());
                if (miner != null) broadcast(miner, dig, CLEAR_STAGE);
                continue;
            }
            entry.setValue(update(miner, dig));
        }
    }

    /** Sends the current stage if it changed; returns the dig carrying it. */
    private Dig update(Player miner, Dig dig) {
        byte stage = stage(miner, dig);
        if (stage == dig.lastStage()) return dig;
        broadcast(miner, dig, stage);
        return new Dig(dig.instance(), dig.pos(), dig.startTick(), stage);
    }

    private static byte stage(Player miner, Dig dig) {
        Block block = dig.instance().getBlock(dig.pos());
        int breakTicks = BlockBreakCalculation.breakTicks(block, miner);
        if (breakTicks == BlockBreakCalculation.UNBREAKABLE) return 0;
        long ticksSpent = TickSystem.instanceTick(dig.instance()) - dig.startTick();
        // vanilla: destroyProgress * (ticks+1), stage = progress*10, unclamped (out-of-range clears client-side)
        return (byte) ((ticksSpent + 1) * 10 / Math.max(1, breakTicks));
    }

    private static void broadcast(Player miner, Dig dig, byte stage) {
        var packet = new BlockBreakAnimationPacket(miner.getEntityId(), dig.pos(), stage);
        for (Player viewer : dig.instance().getPlayers()) {
            if (viewer == miner) continue;
            Pos at = viewer.getPosition();
            double xd = dig.pos().x() - at.x(), yd = dig.pos().y() - at.y(), zd = dig.pos().z() - at.z();
            if (xd * xd + yd * yd + zd * zd < BROADCAST_RANGE_SQ) viewer.sendPacket(packet);
        }
    }
}
