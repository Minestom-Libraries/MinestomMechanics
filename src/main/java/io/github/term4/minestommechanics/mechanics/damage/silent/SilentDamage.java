package io.github.term4.minestommechanics.mechanics.damage.silent;

import io.github.term4.minestommechanics.tracking.ClientInfoTracker;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;

/**
 * Applies health changes without triggering the client-side hurt effect (camera shake / red flash).
 *
 * <ul>
 *   <li>Legacy (1.8) clients: suppress the outgoing {@code UpdateHealth}/{@code EntityAttributes}
 *       packets (via {@link HurtSuppression}), {@code setHealth} to keep the server in sync, then send
 *       the entity metadata packet. The metadata health index updates the bar without the tilt.</li>
 *   <li>Modern/unknown clients: the max-health trick (temporarily set MAX_HEALTH to the new health so
 *       the client sees a "full" bar, setHealth, then restore max).</li>
 * </ul>
 */
public final class SilentDamage {

    private SilentDamage() {}

    public static void setHealthWithoutHurtEffect(Player player, float newHealth, ClientInfoTracker clientInfo) {
        boolean legacy = clientInfo != null && clientInfo.isLegacy(player);

        if (legacy) {
            HurtSuppression.setSuppressHealthPackets(player, true);
            try {
                player.setHealth(newHealth);
                player.sendPacket(player.getMetadataPacket());
            } finally {
                HurtSuppression.setSuppressHealthPackets(player, false);
            }
            return;
        }

        var maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double originalBase = maxAttr.getBaseValue();
        maxAttr.setBaseValue(Math.max(0.5, newHealth)); // min 0.5 for attribute validity
        player.setHealth(newHealth);
        maxAttr.setBaseValue(originalBase);
    }
}
