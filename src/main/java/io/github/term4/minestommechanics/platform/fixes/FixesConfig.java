package io.github.term4.minestommechanics.platform.fixes;

import io.github.term4.minestommechanics.platform.fixes.client.LegacyEquipmentFixConfig;
import io.github.term4.minestommechanics.platform.fixes.client.LegacyTabCompleteFixConfig;
import io.github.term4.minestommechanics.platform.fixes.client.LegacyViewDistanceFixConfig;
import io.github.term4.minestommechanics.platform.fixes.client.SelfPlacementFixConfig;
import io.github.term4.minestommechanics.platform.fixes.visuals.VisualsConfig;
import io.github.term4.minestommechanics.platform.fixes.world.BlockBreakProgressFixConfig;
import io.github.term4.minestommechanics.platform.fixes.world.BlockPlacementFixConfig;
import org.jetbrains.annotations.Nullable;

/**
 * Top-level config for the lib's client/protocol behavior <b>fixes</b> - covering both cross-version compatibility
 * fixes (papering over how an older client behaves, e.g. the 1.8 arrow-visibility fix) and single-version client
 * smoothing (a modern client's own prediction echo, e.g. the self-meta fix). Assigned per scope via the
 * {@link io.github.term4.minestommechanics.MechanicsProfile} {@code fixes} member.
 */
public final class FixesConfig {

    private final @Nullable VisualsConfig visuals;
    private final @Nullable BlockBreakProgressFixConfig blockBreakProgress;
    private final @Nullable BlockPlacementFixConfig blockPlacement;
    private final @Nullable SelfPlacementFixConfig selfPlacement;
    private final @Nullable LegacyEquipmentFixConfig legacyEquipmentFix;
    private final @Nullable LegacyViewDistanceFixConfig legacyViewDistanceFix;
    private final @Nullable LegacyTabCompleteFixConfig legacyTabCompleteFix;

    private FixesConfig(Builder b) {
        this.visuals = b.visuals;
        this.blockBreakProgress = b.blockBreakProgress;
        this.blockPlacement = b.blockPlacement;
        this.selfPlacement = b.selfPlacement;
        this.legacyEquipmentFix = b.legacyEquipmentFix;
        this.legacyViewDistanceFix = b.legacyViewDistanceFix;
        this.legacyTabCompleteFix = b.legacyTabCompleteFix;
    }

    /** The visual fixes, or {@code null} if unset. */
    public @Nullable VisualsConfig visuals() { return visuals; }
    /** The block-break progress broadcast (crack overlay for other players; Minestom never sends it), or {@code null} if unset. */
    public @Nullable BlockBreakProgressFixConfig blockBreakProgress() { return blockBreakProgress; }
    /** The block-placement sync fix, or {@code null} if unset. */
    public @Nullable BlockPlacementFixConfig blockPlacement() { return blockPlacement; }
    /** The 1.8 self-placement compat fix (place a block into your own body), or {@code null} if unset. */
    public @Nullable SelfPlacementFixConfig selfPlacement() { return selfPlacement; }
    /** The legacy equipment-slot fix (strip empty slots from equipment packets to legacy clients), or {@code null} if unset. */
    public @Nullable LegacyEquipmentFixConfig legacyEquipmentFix() { return legacyEquipmentFix; }
    /** The view-distance clamp fix (cap a client's view distance to the instance's so refreshSettings doesn't over-send chunks), or {@code null} if unset. */
    public @Nullable LegacyViewDistanceFixConfig legacyViewDistanceFix() { return legacyViewDistanceFix; }
    /** The tab-completion fix (answer command-name completion that Minestom ignores) for legacy clients, or {@code null} if unset. */
    public @Nullable LegacyTabCompleteFixConfig legacyTabCompleteFix() { return legacyTabCompleteFix; }

    /** Merges this config over {@code base} (each member: this if set, else base; both set -&gt; member-merged). */
    public FixesConfig fromBase(FixesConfig base) {
        VisualsConfig v = visuals == null ? base.visuals
                : base.visuals == null ? visuals : visuals.fromBase(base.visuals);
        BlockBreakProgressFixConfig bbp = blockBreakProgress == null ? base.blockBreakProgress
                : base.blockBreakProgress == null ? blockBreakProgress : blockBreakProgress.fromBase(base.blockBreakProgress);
        BlockPlacementFixConfig bp = blockPlacement == null ? base.blockPlacement
                : base.blockPlacement == null ? blockPlacement : blockPlacement.fromBase(base.blockPlacement);
        SelfPlacementFixConfig sp = selfPlacement == null ? base.selfPlacement
                : base.selfPlacement == null ? selfPlacement : selfPlacement.fromBase(base.selfPlacement);
        LegacyEquipmentFixConfig le = legacyEquipmentFix == null ? base.legacyEquipmentFix
                : base.legacyEquipmentFix == null ? legacyEquipmentFix : legacyEquipmentFix.fromBase(base.legacyEquipmentFix);
        LegacyViewDistanceFixConfig lvd = legacyViewDistanceFix == null ? base.legacyViewDistanceFix
                : base.legacyViewDistanceFix == null ? legacyViewDistanceFix : legacyViewDistanceFix.fromBase(base.legacyViewDistanceFix);
        LegacyTabCompleteFixConfig ltc = legacyTabCompleteFix == null ? base.legacyTabCompleteFix
                : base.legacyTabCompleteFix == null ? legacyTabCompleteFix : legacyTabCompleteFix.fromBase(base.legacyTabCompleteFix);
        return new Builder().visuals(v).blockBreakProgress(bbp).blockPlacement(bp).selfPlacement(sp).legacyEquipmentFix(le).legacyViewDistanceFix(lvd).legacyTabCompleteFix(ltc).build();
    }

    public Builder toBuilder() { return new Builder(this); }
    public static Builder builder() { return new Builder(); }
    public static Builder builder(@Nullable FixesConfig base) { return base != null ? new Builder(base) : new Builder(); }

    public static final class Builder {
        private @Nullable VisualsConfig visuals;
        private @Nullable BlockBreakProgressFixConfig blockBreakProgress;
        private @Nullable BlockPlacementFixConfig blockPlacement;
        private @Nullable SelfPlacementFixConfig selfPlacement;
        private @Nullable LegacyEquipmentFixConfig legacyEquipmentFix;
        private @Nullable LegacyViewDistanceFixConfig legacyViewDistanceFix;
        private @Nullable LegacyTabCompleteFixConfig legacyTabCompleteFix;

        Builder() {}
        Builder(FixesConfig c) { visuals = c.visuals; blockBreakProgress = c.blockBreakProgress; blockPlacement = c.blockPlacement; selfPlacement = c.selfPlacement; legacyEquipmentFix = c.legacyEquipmentFix; legacyViewDistanceFix = c.legacyViewDistanceFix; legacyTabCompleteFix = c.legacyTabCompleteFix; }

        public Builder visuals(@Nullable VisualsConfig v) { this.visuals = v; return this; }
        public Builder blockBreakProgress(@Nullable BlockBreakProgressFixConfig v) { this.blockBreakProgress = v; return this; }
        public Builder blockPlacement(@Nullable BlockPlacementFixConfig v) { this.blockPlacement = v; return this; }
        public Builder selfPlacement(@Nullable SelfPlacementFixConfig v) { this.selfPlacement = v; return this; }
        public Builder legacyEquipmentFix(@Nullable LegacyEquipmentFixConfig v) { this.legacyEquipmentFix = v; return this; }
        public Builder legacyViewDistanceFix(@Nullable LegacyViewDistanceFixConfig v) { this.legacyViewDistanceFix = v; return this; }
        public Builder legacyTabCompleteFix(@Nullable LegacyTabCompleteFixConfig v) { this.legacyTabCompleteFix = v; return this; }

        public FixesConfig build() { return new FixesConfig(this); }
    }
}
