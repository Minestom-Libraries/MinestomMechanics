package io.github.term4.minestommechanics.platform.player;

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
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * The library's custom {@link Player}, with two independent behaviors, both opt-in via {@code MinestomMechanics}.
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
 * <p>Ported from the standalone {@code minestom-echo-fix} so the library has no external dependency.
 */
public class OptimizedPlayer extends Player {

    // --- self-echo meta fix ---
    private @Nullable SelfMetaFilter selfMetaFilter = SelfMetaFilter.defaultPlayerFilter();
    private boolean processingClientInput = false;

    // --- position-broadcast throttle ---
    private int positionBroadcastInterval = 1;

    // --- self-placement exclusion hook (driven by fixes.client.SelfPlacementFix) ---
    private boolean selfPlacing = false;

    // --- cross-version compat: poses forced back to standing + hitbox-collision movement restriction + legacy hitbox/eye (driven by CompatConfig via PlayerConfigApplier) ---
    private Set<EntityPose> disabledPoses = Set.of();
    private boolean restrictMovement = false;
    private boolean legacyHitbox = false;

    /** 1.8 sneaking eye height for the legacy server-eye preset (1.8 standing 1.62 already = Minestom's default; modern crouch is lower). */
    private static final double LEGACY_SNEAKING_EYE = 1.54;

    public OptimizedPlayer(PlayerConnection connection, GameProfile gameProfile) {
        super(connection, gameProfile);
    }

    /**
     * Marks whether the packet currently being processed originates from client input. Set by {@code MetaFix}
     * around the client-input listeners; while {@code true}, self-bound echoes are filtered.
     *
     * @param value true when processing a client input packet
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
     * Arms the self-echo filter around tick-driven pose recalculation. {@link Player#updatePose()} runs every server
     * tick from {@code update()} (not only inside a client packet listener), recomputing the pose from whether the
     * player still fits their space - this is where crawl enter/exit happens (e.g. squeezing under a closing trapdoor).
     * Without arming the flag here that self-bound pose echo slips through unfiltered, causing the crawl/stand stutter;
     * {@link SelfMetaFilter} already suppresses the pose index, it just needs the flag set. Save/restore the previous
     * value so it nests safely if a packet listener already armed it. A direct {@code setPose(...)} does not route
     * through here, so server-authoritative poses still echo.
     */
    @Override
    protected void updatePose() {
        boolean previous = processingClientInput;
        processingClientInput = true;
        try {
            super.updatePose();
        } finally {
            processingClientInput = previous;
        }
    }

    /**
     * Cross-version compat: a {@code CompatConfig}-disabled pose is rewritten to {@link EntityPose#STANDING} <em>before</em>
     * it reaches metadata, so the player genuinely never enters it - nothing visible to self or viewers, matching a 1.8
     * server. {@link #updatePose()} routes both the swim flag and the squeeze-to-fit crawl through {@code setPose}, so
     * intercepting here covers both at the source (no after-the-fact flip-flop). The metadata layer dedups the repeated
     * {@code STANDING} write, so a client holding a disabled input costs a single correction packet, not per-tick spam.
     * Sent with the self-echo guard cleared so the {@code STANDING} correction reaches the mispredicting client too.
     */
    @Override
    public void setPose(@NotNull EntityPose pose) {
        if (disabledPoses != null && disabledPoses.contains(pose)) {
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

    /** Poses rewritten to {@code STANDING} in {@link #setPose} (pushed from {@code CompatConfig} by {@code PlayerConfigApplier}). */
    public void setDisabledPoses(@NotNull Set<EntityPose> poses) { this.disabledPoses = poses; }

    public @NotNull Set<EntityPose> getDisabledPoses() { return disabledPoses; }

    /** Whether moves into hitbox-block collision are rejected (compat; enforced by {@code CompatMovement}). Pushed from {@code CompatConfig}. */
    public void setRestrictMovement(boolean v) { this.restrictMovement = v; }

    public boolean isRestrictMovement() { return restrictMovement; }

    /** Whether the server hitbox/eye height stay at 1.8 dimensions regardless of pose (no crouch shrink). Pushed from {@code CompatConfig}. */
    public void setLegacyHitbox(boolean v) { this.legacyHitbox = v; }

    public boolean isLegacyHitbox() { return legacyHitbox; }

    /**
     * Cross-version compat: with {@link #setLegacyHitbox legacy hitbox} on, the server box stays standing (the {@code boundingBox}
     * field) regardless of pose, so a modern client's crouch/crawl shrink doesn't apply server-side (1.8 parity; lets
     * {@code CompatMovement} block the 1.5-block sneak gap). Disabled poses already resolve to standing, so this only adds the sneak case.
     */
    @Override
    public BoundingBox getBoundingBox() {
        return legacyHitbox ? boundingBox : super.getBoundingBox();
    }

    /**
     * The SERVER-treated eye height - the preset consumed by projectile spawn, drowning and suffocation. With
     * {@code legacyHitbox} on it's the fixed 1.8 preset (sneaking 1.54, else the 1.62 standing default), so a crouching
     * modern client still spawns/drowns at the 1.8 eye; off, it's Minestom's native value. This is value (b) of the
     * eye-height model; value (a) - what the client believes, for reach/raytrace - is derived from the protocol separately.
     */
    @Override
    public double getEyeHeight() {
        if (legacyHitbox && getPose() == EntityPose.SNEAKING) return LEGACY_SNEAKING_EYE;
        return super.getEyeHeight();
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
        // Technically api internal but it works. Bite me.
        super.refreshPosition(newPosition, ignoreView, sendPackets);
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
