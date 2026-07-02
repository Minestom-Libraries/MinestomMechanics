package io.github.term4.minestommechanics.platform.fixes.client;

import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import io.github.term4.minestommechanics.platform.PacketShapes;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;

import java.util.EnumMap;
import java.util.Map;

/**
 * Equipment-slot fix: strips empty (AIR) slots from outgoing {@code EntityEquipmentPacket}s, matching vanilla (which only sends
 * slots that actually hold an item - {@code ServerEntity} skips empty ones).
 *
 * <p><b>Why:</b> Minestom's {@code getEquipmentsPacket} sends every {@code EquipmentSlot} including {@code BODY} as AIR for a
 * player. ViaBackwards ({@code v1_20_5to1_20_3}) remaps the BODY slot (6) onto the CHESTPLATE slot (4) - correct for horse/wolf
 * body armor on old clients - so the player's stray {@code BODY=AIR} becomes a second chestplate-slot entry that's empty. The
 * client applies the two slot-4 entries in order (last wins); Minestom's {@code Map.copyOf} randomises that order per JVM run, so
 * the real chestplate intermittently gets overwritten by the AIR and renders invisible. Dropping the empty slots removes the stray
 * BODY entry, so nothing collides.
 *
 * <p>Applied to ALL clients (not gated on {@code isLegacy}): dropping empty slots is what vanilla does, so it's harmless for a
 * modern client, and gating on the legacy check leaked through the join window (the equipment of existing players is sent to a
 * just-joined viewer before its protocol is resolved). A single-slot AIR update (clearing a slot) is left untouched. Hooked from
 * both {@link io.github.term4.minestommechanics.platform.player.OptimizedPlayer#sendPacket} (per-viewer / new-viewer sends) and
 * {@code OptimizedPlayer#sendPacketToViewers} (the grouped bulk resend on respawn/teleport, before it becomes a CachedPacket the
 * per-viewer hook can't unwrap).
 *
 * <p><b>Temporary:</b> this is the server-side workaround. The proper fix patches Minestom's {@code getEquipmentsPacket} to
 * skip empty slots like vanilla (upstream branch {@code fix/skip-empty-equipment-slots}); once that is merged and on the
 * pinned Minestom dependency, this fix can be removed.
 */
public final class LegacyEquipmentFix {

    /** {@code true} once installed/enabled (set by {@code FixesSystem}); {@code false} = the fix is off. */
    private static volatile boolean enabled;

    private LegacyEquipmentFix() {}

    /** Enables the fix. Called by {@code FixesSystem.install} when the install config turns it on. */
    public static void install() {
        enabled = true;
    }

    /** Strips empty equipment slots from an {@code EntityEquipmentPacket}; otherwise returns {@code packet} unchanged. */
    public static SendablePacket rewrite(SendablePacket packet) {
        if (!enabled) return packet;
        final ServerPacket serverPacket = PacketShapes.unwrapStateless(packet);
        if (!(serverPacket instanceof EntityEquipmentPacket equipment)) return packet;

        final Map<EquipmentSlot, ItemStack> kept = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.equipments().entrySet()) {
            if (!entry.getValue().isAir()) kept.put(entry.getKey(), entry.getValue());
        }
        // No empty slots to drop, or all empty (can't build an empty packet, and a single-slot AIR is a legit slot-clear).
        if (kept.size() == equipment.equipments().size() || kept.isEmpty()) return packet;
        return new EntityEquipmentPacket(equipment.entityId(), kept);
    }
}
