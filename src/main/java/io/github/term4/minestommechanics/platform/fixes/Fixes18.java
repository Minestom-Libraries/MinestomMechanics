package io.github.term4.minestommechanics.platform.fixes;

import io.github.term4.minestommechanics.platform.fixes.client.LegacyEquipmentFixConfig;
import io.github.term4.minestommechanics.platform.fixes.client.LegacyTabCompleteFixConfig;
import io.github.term4.minestommechanics.platform.fixes.client.LegacyViewDistanceFixConfig;
import io.github.term4.minestommechanics.platform.fixes.client.SelfPlacementFixConfig;
import io.github.term4.minestommechanics.platform.fixes.visuals.VisualsConfig;
import io.github.term4.minestommechanics.platform.fixes.visuals.legacy_1_8.LegacyArrowVisibilityConfig;
import io.github.term4.minestommechanics.platform.fixes.world.BlockBreakProgressFixConfig;
import io.github.term4.minestommechanics.platform.fixes.world.BlockPlacementFixConfig;

/**
 * Legacy-client fix bundle: the {@link FixesConfig} that makes 1.8/Via clients render and behave correctly - arrow
 * visibility, block-placement sync, self-placement, and the legacy equipment / view-distance / tab-complete fixes.
 * General; install with {@code FixesSystem.install(mm, Fixes18.config())}.
 */
public final class Fixes18 {

    private Fixes18() {}

    /** The full legacy-client fix set. */
    public static FixesConfig config() {
        return FixesConfig.builder()
                .visuals(VisualsConfig.builder()
                        .legacyArrowVisibility(LegacyArrowVisibilityConfig.builder().enabled(true).deflectParticles(true).build())
                        .build())
                .blockBreakProgress(BlockBreakProgressFixConfig.builder().enabled(true).build()) // crack overlay for other players (Minestom never broadcasts it, any client version)
                .blockPlacement(BlockPlacementFixConfig.builder().enabled(true).build()) // TODO: Remove after PR is merged to minestom
                .selfPlacement(SelfPlacementFixConfig.builder().enabled(true).build()) // For 1.8 clients so they can place ladders while climbing up
                .legacyEquipmentFix(LegacyEquipmentFixConfig.builder().enabled(true).build()) // strip empty equip slots for legacy clients (fixes BODY=AIR -> chestplate-invisible)
                .legacyViewDistanceFix(LegacyViewDistanceFixConfig.builder().enabled(true).build()) // clamp view distance to instance's (fixes the legacy invisibility band far from spawn)
                .legacyTabCompleteFix(LegacyTabCompleteFixConfig.builder().enabled(true).build()) // answer command-name tab completion that Minestom ignores (fixes /gm -> gmc/gms on 1.8/Via)
                .build();
    }
}
