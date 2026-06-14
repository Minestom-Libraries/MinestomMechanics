package io.github.term4.minestommechanics.tracking;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Tracks client details relevant to this library - for now only the protocol version, stamped from the
 * ViaVersion proxy-details plugin message. May later cover client-mod handshakes (Lunar/Apollo, Badlion,
 * Animatium, Combatify, ...).
 */
public final class ClientInfoTracker implements Tracker {

    /** ViaVersion proxy channel carrying {@code {"version": <protocol>, ...}} JSON. */
    public static final String VIA_PROXY_DETAILS_CHANNEL = "vv:proxy_details";
    public static final int UNKNOWN_PROTOCOL = -1;
    /** Highest protocol version still treated as legacy (1.8.x = 47). */
    public static final int LEGACY_PROTOCOL_MAX = 47;

    private static final Tag<ClientInfo> CLIENT_INFO = Tag.Transient("mm:client-info");

    /** Raw proxy-details JSON plus the lazily parsed protocol; transient, so it dies with the player object. */
    private static final class ClientInfo {
        String proxyDetails;
        Integer cachedProtocol;
    }

    @Override
    public EventNode<@NotNull PlayerEvent> node() {
        EventNode<@NotNull PlayerEvent> node = EventNode.type("mm:client-info-tracker", EventFilter.PLAYER);
        node.addListener(PlayerPluginMessageEvent.class, e -> {
            if (!VIA_PROXY_DETAILS_CHANNEL.equals(e.getIdentifier())) return;
            byte[] data = e.getMessage();
            if (data.length == 0) return;
            setProxyDetails(e.getPlayer(), new String(data, StandardCharsets.UTF_8)); // payload is UTF-8 JSON
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
    }

    public @Nullable String getProxyDetails(Player player) {
        ClientInfo info = player.getTag(CLIENT_INFO);
        return info == null ? null : info.proxyDetails;
    }

    /**
     * The player's protocol version from the ViaVersion proxy details, or {@link #UNKNOWN_PROTOCOL} until that
     * plugin message arrives - it lands shortly after login (not during the initial join events), so handle the
     * UNKNOWN case or defer briefly.
     */
    public int getProtocol(Player player) {
        ClientInfo info = player.getTag(CLIENT_INFO);
        if (info == null) return UNKNOWN_PROTOCOL;
        if (info.cachedProtocol != null) return info.cachedProtocol;

        String json = info.proxyDetails;
        if (json == null || json.isEmpty()) return UNKNOWN_PROTOCOL;

        int parsed = parseProtocol(json);
        if (parsed != UNKNOWN_PROTOCOL) info.cachedProtocol = parsed; // cache only valid parses
        return parsed;
    }

    /**
     * Whether {@code player}'s tracked protocol is a known legacy ({@code <= 1.8.x}) client. Returns {@code false}
     * while the protocol is still {@link #UNKNOWN_PROTOCOL} (proxy details arrive shortly after login, not during the
     * join events) - callers that must not misclassify during that window should check {@link #getProtocol} directly.
     */
    public boolean isLegacy(Player player) {
        int protocol = getProtocol(player);
        return protocol != UNKNOWN_PROTOCOL && protocol <= LEGACY_PROTOCOL_MAX;
    }

    private static int parseProtocol(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            var version = obj.get("version");
            return (version != null && version.isJsonPrimitive()) ? version.getAsInt() : UNKNOWN_PROTOCOL;
        } catch (Exception ignored) {
            return UNKNOWN_PROTOCOL;
        }
    }
}
