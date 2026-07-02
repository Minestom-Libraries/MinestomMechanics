package io.github.term4.minestommechanics.tracking;

import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The inbound plugin-message hub: a single {@link PlayerPluginMessageEvent} listener that handles the ViaVersion
 * proxy-details channel (protocol detection, delegated to {@link ClientVersion}) and routes other channels to registered
 * mod handlers (Animatium today). Per-player client info (raw proxy-details JSON + lazily parsed protocol) lives in a
 * transient tag, so it dies with the player object.
 */
public final class ClientInfoTracker implements Tracker {

    /** ViaVersion proxy channel carrying {@code {"version": <protocol>, ...}} JSON. */
    public static final String VIA_PROXY_DETAILS_CHANNEL = "vv:proxy_details";

    private static final Tag<ClientInfo> CLIENT_INFO = Tag.Transient("mm:client-info");

    /** Whether to parse the ViaVersion proxy-details channel (server option {@code viaProxyDetails}). */
    private final boolean handleProxyDetails;
    /** Channel -> handler for client-mod handshakes; populated at install (e.g. {@code CompatAnimatium}), read per message. */
    private final Map<String, BiConsumer<Player, byte[]>> modHandlers = new HashMap<>();

    public ClientInfoTracker(boolean handleProxyDetails) {
        this.handleProxyDetails = handleProxyDetails;
    }

    /** Raw proxy-details JSON plus the lazily parsed protocol; transient, so it dies with the player object. */
    private static final class ClientInfo {
        String proxyDetails;
        Integer cachedProtocol;
    }

    /**
     * Registers a handler for a client-mod handshake channel (its inbound plugin messages are routed here). The handler
     * runs on the network thread with the raw payload bytes; keep it cheap. Call once at install, before any player joins.
     */
    public void onPluginMessage(@NotNull String channel, @NotNull BiConsumer<Player, byte[]> handler) {
        modHandlers.put(channel, handler);
    }

    @Override
    public EventNode<@NotNull PlayerEvent> node() {
        EventNode<@NotNull PlayerEvent> node = EventNode.type("mm:client-info-tracker", EventFilter.PLAYER);
        node.addListener(PlayerPluginMessageEvent.class, e -> {
            String channel = e.getIdentifier();
            byte[] data = e.getMessage();
            if (handleProxyDetails && VIA_PROXY_DETAILS_CHANNEL.equals(channel)) {
                if (data.length != 0) setProxyDetails(e.getPlayer(), new String(data, StandardCharsets.UTF_8)); // payload is UTF-8 JSON
                return;
            }
            BiConsumer<Player, byte[]> handler = modHandlers.get(channel);
            if (handler != null) handler.accept(e.getPlayer(), data);
        });
        return node;
    }

    /** Stores ViaVersion proxy-details JSON and invalidates the cached protocol. */
    public void setProxyDetails(Player player, String proxyDetails) {
        ClientInfo info = player.getTag(CLIENT_INFO);
        if (info == null) {
            info = new ClientInfo();
            player.setTag(CLIENT_INFO, info);
        }
        info.proxyDetails = proxyDetails;
        info.cachedProtocol = null;
        // legacy resolved: flag compat (stops the attack_range stamp, which only reaches a 1.8 client as junk NBT
        // through Via) and re-send the inventory once so items stamped during the unknown-protocol window go out clean
        if (player instanceof OptimizedPlayer op && ClientVersion.isLegacy(getProtocol(player)) && !op.compat().legacyClient()) {
            op.compat().setLegacyClient(true);
            if (op.compat().attackHitboxMargin() != null) op.getInventory().update();
        }
    }

    public @Nullable String getProxyDetails(Player player) {
        ClientInfo info = player.getTag(CLIENT_INFO);
        return info == null ? null : info.proxyDetails;
    }

    /**
     * The player's protocol version from the ViaVersion proxy details, or {@link ClientVersion#UNKNOWN_PROTOCOL} until that
     * plugin message arrives - it lands shortly after login (not during the initial join events), so handle the UNKNOWN
     * case or defer briefly.
     */
    public int getProtocol(Player player) {
        ClientInfo info = player.getTag(CLIENT_INFO);
        if (info == null) return ClientVersion.UNKNOWN_PROTOCOL;
        if (info.cachedProtocol != null) return info.cachedProtocol;

        int parsed = ClientVersion.parse(info.proxyDetails);
        if (parsed != ClientVersion.UNKNOWN_PROTOCOL) info.cachedProtocol = parsed; // cache only valid parses
        return parsed;
    }

    /** A per-player {@link ClientProfile} view; also reachable via {@code mm.client(player)}. */
    public ClientProfile of(Player player) {
        return new ClientProfile(player, this);
    }

    /**
     * Whether {@code player}'s tracked protocol is a known legacy ({@code <= 1.8.x}) client. Returns {@code false} while the
     * protocol is still {@link ClientVersion#UNKNOWN_PROTOCOL} (proxy details arrive shortly after login, not during the
     * join events) - callers that must not misclassify during that window should check {@link #getProtocol} directly.
     */
    public boolean isLegacy(Player player) {
        return ClientVersion.isLegacy(getProtocol(player));
    }
}
