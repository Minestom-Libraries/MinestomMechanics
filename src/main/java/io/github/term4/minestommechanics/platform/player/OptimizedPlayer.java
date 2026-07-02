package io.github.term4.minestommechanics.platform.player;

import io.github.term4.minestommechanics.platform.compatibility.CompatState;
import io.github.term4.minestommechanics.platform.fixes.client.LegacyEquipmentFix;
import io.github.term4.minestommechanics.platform.fixes.client.LegacyViewDistanceFix;
import io.github.term4.minestommechanics.platform.fixes.client.SelfMetaFilter;
import io.github.term4.minestommechanics.util.tick.TickScaler;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityAttributesPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.player.ClientSettings;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The library's custom {@link Player}, hosting several independent opt-in behaviors wired by {@code MinestomMechanics}.
 *
 * <p><b>Self-echo meta fix:</b> suppresses client-predicted metadata/attribute echoes (sneak, sprint, pose, item use)
 * so a high-ping 1.9+ client does not stutter for one tick when the server broadcasts state the client already
 * predicted. Driven by {@code MetaFix}, which flips {@link #setProcessingClientInput} around the client-input
 * listeners; the filtering lives in {@link #sendPacketToViewersAndSelf} + {@link SelfMetaFilter}. Viewers always get
 * the full packet.
 *
 * <p><b>Position-broadcast throttle:</b> drops a fraction of position broadcasts to viewers (Spigot-style),
 * configured by {@link #setPositionBroadcastInterval}.
 *
 * <p><b>Self-placement exclusion:</b> lets a player place a passable block into their own body while
 * {@link #setSelfPlacing self-placing} (driven by {@code fixes.client.SelfPlacementFix}).
 *
 * <p><b>Cross-version compat:</b> serves 1.8-style mechanics to modern clients - pose disabling ({@link #setPose}),
 * legacy hitbox/eye ({@link #getBoundingBox}/{@link #getEyeHeight}) and the attack-box margin ({@link #sendPacket}).
 * The state + policy live in {@link CompatState} (accessed via {@link #compat()}); these overrides only route to it.
 * Pushed from {@code CompatConfig} by {@code PlayerConfigApplier}.
 *
 * <p>The echo fix + throttle were ported from the standalone {@code minestom-echo-fix} so the library has no external dependency.
 */
public class OptimizedPlayer extends Player {

    // --- self-echo meta fix ---
    private @Nullable SelfMetaFilter selfMetaFilter = SelfMetaFilter.defaultPlayerFilter();
    private boolean processingClientInput = false;

    // --- position-broadcast throttle ---
    private int positionBroadcastInterval = 1;

    // --- self-placement exclusion hook (driven by fixes.client.SelfPlacementFix) ---
    private boolean selfPlacing = false;

    // --- cross-version compat state + logic (pushed from CompatConfig by PlayerConfigApplier; the overrides below route here) ---
    private final CompatState compat = new CompatState();

    public OptimizedPlayer(PlayerConnection connection, GameProfile gameProfile) {
        super(connection, gameProfile);
    }

    /**
     * Marks whether the packet currently being processed originates from client input. Set by {@code MetaFix}
     * around the client-input listeners; while {@code true}, self-bound echoes are filtered.
     */
    public void setProcessingClientInput(boolean value) {
        this.processingClientInput = value;
    }

    /** The active self-metadata filter, or {@code null} when filtering is disabled. */
    public @Nullable SelfMetaFilter getSelfMetaFilter() {
        return selfMetaFilter;
    }

    /** Sets the self-metadata filter, or {@code null} to disable filtering for this player. */
    public void setSelfMetaFilter(@Nullable SelfMetaFilter filter) {
        this.selfMetaFilter = filter;
    }

    /**
     * Updates player state without sending the change to this player; other viewers still receive it.
     * <pre>{@code
     * player.suppressSelf(() -> player.setSneaking(true));
     * }</pre>
     *
     * @param action the state change to suppress from self
     */
    public void suppressSelf(@NotNull Runnable action) {
        processingClientInput = true;
        try {
            action.run();
        } finally {
            processingClientInput = false;
        }
    }

    @Override
    public void sendPacketToViewersAndSelf(@NotNull SendablePacket packet) {
        if (processingClientInput && selfMetaFilter != null) {

            // Metadata filtering (crouching, use item, start elytra fly)
            if (packet instanceof EntityMetaDataPacket(int entityId, Map<Integer, Metadata.Entry<?>> entries) && entityId == getEntityId()) {
                Map<Integer, Metadata.Entry<?>> filtered = selfMetaFilter.filter(entries);
                if (filtered != null) {
                    if (!filtered.isEmpty()) {
                        sendPacket(new EntityMetaDataPacket(entityId, filtered));
                    }
                    sendPacketToViewers(packet);
                    return;
                }
            }

            // Attribute filter (e.g. sprint)
            if (packet instanceof EntityAttributesPacket attr
                    && attr.entityId() == getEntityId()
                    && selfMetaFilter.suppressAttributes()) {
                sendPacketToViewers(packet);
                return;
            }
        }

        super.sendPacketToViewersAndSelf(packet);
    }

    /**
     * Outgoing-packet transforms: {@link CompatState#stampAttackRange} stamps the {@code attack_range} component onto the items a
     * client sees (so a modern 1.21.11+ client attacks with the 1.8 hitbox box), then {@link LegacyEquipmentFix#rewrite} strips
     * empty equipment slots from {@code EntityEquipmentPacket}s (vanilla parity; avoids the ViaBackwards BODY-&gt;chestplate
     * collision). Both no-op when their fix is off, so the hot path stays cheap.
     */
    @Override
    public void sendPacket(@NotNull SendablePacket packet) {
        super.sendPacket(LegacyEquipmentFix.rewrite(compat.stampAttackRange(packet)));
    }

    /**
     * Bulk equipment resends to viewers (respawn/teleport {@code refreshAfterTeleport}) get grouped into a
     * {@code CachedPacket} that the per-viewer {@link #sendPacket} transform can't unwrap - so strip empty slots here, on
     * the still-bare packet, before grouping. Otherwise the stray {@code BODY=AIR} reaches ViaBackwards and the chestplate
     * renders invisible on legacy clients after respawn.
     */
    @Override
    public void sendPacketToViewers(@NotNull SendablePacket packet) {
        super.sendPacketToViewers(LegacyEquipmentFix.rewrite(packet));
    }

    /**
     * Arms the self-echo filter around tick-driven pose recalculation. {@link Player#updatePose()} runs every server tick
     * (not only in a packet listener), recomputing the pose from whether the player still fits - where crawl enter/exit
     * happens. Without arming the flag here the self-bound pose echo slips through, causing the crawl/stand stutter
     * ({@link SelfMetaFilter} suppresses the pose index, it just needs the flag). Save/restore so it nests if a listener
     * already armed it; a direct {@code setPose} doesn't route here, so server-authoritative poses still echo.
     */
    @Override
    protected void updatePose() {
        boolean previous = processingClientInput;
        processingClientInput = true;
        compat.resetInterceptedPose(); // reset each tick; setPose (called by super.updatePose) re-captures any disabled pose Minestom computes now
        try {
            super.updatePose();
        } finally {
            processingClientInput = previous;
        }
    }

    /**
     * Cross-version compat: a {@code CompatConfig}-disabled pose is rewritten to {@link EntityPose#STANDING} <em>before</em>
     * it reaches metadata, so the player never enters it (nothing visible to self or viewers, like a 1.8 server). {@link #updatePose()}
     * routes both the swim flag and the squeeze-crawl through {@code setPose}, so intercepting here covers both at the source.
     * The metadata layer dedups the repeated {@code STANDING} write (a held input = one correction packet, not per-tick spam); sent with the self-echo guard cleared so the correction reaches the mispredicting client.
     */
    @Override
    public void setPose(@NotNull EntityPose pose) {
        if (compat.isPoseDisabled(pose)) {
            compat.recordInterceptedPose(pose); // remember the pose the client believes it's in, even as we force STANDING server-side
            boolean previous = processingClientInput;
            processingClientInput = false;
            try {
                super.setPose(EntityPose.STANDING);
            } finally {
                processingClientInput = previous;
            }
            return;
        }
        super.setPose(pose);
    }

    /** This player's cross-version compat state + logic (pose/hitbox/eye/attack-box). Driven by {@code CompatConfig} via {@code PlayerConfigApplier}; read by {@code CompatMovement}, {@code ClientEye} and the reach guard. */
    public @NotNull CompatState compat() { return compat; }

    /**
     * Cross-version compat: with {@code legacyHitbox} on, the server box stays standing (the {@code boundingBox} field)
     * regardless of pose, so a modern client's crouch/crawl shrink doesn't apply server-side (1.8 parity; lets
     * {@code CompatMovement} block the 1.5-block sneak gap). Disabled poses already resolve to standing, so this only adds the sneak case.
     */
    @Override
    public BoundingBox getBoundingBox() {
        return compat.legacyHitbox() ? boundingBox : super.getBoundingBox();
    }

    /**
     * The SERVER-treated eye height (value (b) of the eye model) - the preset consumed by projectile spawn, drowning and
     * suffocation. {@link CompatState#eyeHeight} applies the fixed 1.8 preset when {@code legacyHitbox} is on, else Minestom's
     * native value. Value (a) - what the client believes, for reach/raytrace - is {@code ClientEye}, derived from the protocol.
     */
    @Override
    public double getEyeHeight() {
        return compat.eyeHeight(super.getEyeHeight(), getPose());
    }

    /**
     * How often this player's position is broadcast to viewers.
     * 1 = every tick (default Minestom), 2 = every other tick (Spigot).
     */
    public void setPositionBroadcastInterval(int interval) {
        if (interval < 1) throw new IllegalArgumentException("interval must be >= 1");
        this.positionBroadcastInterval = interval;
    }

    public int getPositionBroadcastInterval() {
        return positionBroadcastInterval;
    }

    @Override
    public void refreshPosition(Pos newPosition, boolean ignoreView, boolean sendPackets) {
        if (sendPackets) {
            // broadcast at the client's rate: identity at 20, throttled toward ~client Hz as server TPS rises (saves packets)
            int cadence = TickScaler.clientCadence(positionBroadcastInterval);
            if (cadence > 1 && getAliveTicks() % cadence != 0) sendPackets = false;
        }
        // api-internal override, but it works.
        super.refreshPosition(newPosition, ignoreView, sendPackets);
    }

    /**
     * Routes client settings through {@link LegacyViewDistanceFix} - a temporary workaround that clamps the reported
     * view distance to the instance's so Minestom's {@code refreshSettings} does not over-send chunks (the legacy-client
     * "invisibility band" far from spawn). No-op when the fix is off or the client is already within the cap.
     */
    @Override
    public void refreshSettings(@NotNull ClientSettings settings) {
        super.refreshSettings(LegacyViewDistanceFix.clamp(getInstance(), settings));
    }

    /**
     * Arms the self-placement exclusion for the duration of this player's own block placement (lets a passable block
     * be placed into the player's own body). Driven by {@code fixes.client.SelfPlacementFix}; while {@code true},
     * {@link #preventBlockPlacement()} returns false.
     *
     * @param value true while this player's own placement is being processed
     */
    public void setSelfPlacing(boolean value) {
        this.selfPlacing = value;
    }

    /**
     * Skips the self-collision check while {@link #setSelfPlacing(boolean) self-placing}. The 1.8 self-placement fix
     * that drives this - and the policy of when to arm it - lives in {@code fixes.client.SelfPlacementFix}.
     */
    @Override
    public boolean preventBlockPlacement() {
        return !selfPlacing && super.preventBlockPlacement();
    }
}
