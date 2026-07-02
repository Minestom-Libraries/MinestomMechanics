package io.github.term4.minestommechanics.testsupport;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fully joined player over a packet-capturing connection, for integration tests inside the shared
 * {@link HeadlessServerTest} process (Minestom's {@code TestConnection} spins its own {@code Env} server, which
 * clashes with the one-per-JVM harness). Same join flow as {@code TestConnectionImpl}: create through the
 * ConnectionManager (so the installed OptimizedPlayer provider applies), run configuration -> play off-thread.
 */
public final class FakePlayer {

    public final Player player;
    public final List<SendablePacket> sent = new CopyOnWriteArrayList<>();

    private FakePlayer(Player player) { this.player = player; }

    public static FakePlayer connect(@NotNull Instance instance, @NotNull Pos pos, @NotNull String name) {
        var holder = new FakePlayer[1];
        var connection = new PlayerConnection() {
            @Override public void sendPacket(@NotNull SendablePacket packet) { holder[0].sent.add(packet); }
            @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("localhost", 25565); }
            @Override public void disconnect() {}
        };
        var manager = MinecraftServer.getConnectionManager();
        CompletableFuture<Void> joined = new CompletableFuture<>();
        // createPlayer asserts it runs on a virtual thread (the real login path), so the whole join runs on one
        Thread.startVirtualThread(() -> {
            Player player = manager.createPlayer(connection, new GameProfile(UUID.randomUUID(), name));
            holder[0] = new FakePlayer(player);
            player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, e -> {
                e.setSpawningInstance(instance);
                e.getPlayer().setRespawnPoint(pos);
            });
            manager.doConfiguration(player, false);
            manager.transitionConfigToPlay(player);
            joined.complete(null);
        });
        joined.join();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        manager.updateWaitingPlayers();
        return holder[0];
    }

    /** The captured packets of {@code type}, in send order. */
    public <T extends SendablePacket> List<T> sent(Class<T> type) {
        return sent.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
