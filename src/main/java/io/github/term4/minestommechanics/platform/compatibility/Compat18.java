package io.github.term4.minestommechanics.platform.compatibility;

import net.minestom.server.entity.EntityPose;

/**
 * Cross-version compatibility presets that make a modern (26.1) client behave like 1.8. General - not tied to any
 * mechanics preset, usable from anywhere. {@link #config()} is the full 1.8 compat set; {@link #off()} is the inverse
 * (every knob OFF) for toggling back to vanilla 26.1. Applied via {@code MechanicsProfile.compat} (pushed to
 * {@code OptimizedPlayer} by {@code PlayerConfigApplier}).
 */
public final class Compat18 {

    private Compat18() {}

    /** The full 1.8 compatibility set: 1.8 poses/hitbox/eye/attack-box/offhand/sprint/placement + the Animatium client features. */
    public static CompatConfig config() {
        return CompatConfig.builder()
                .disabledPoses(EntityPose.SWIMMING, EntityPose.FALL_FLYING)
                .restrictMovement(true)
                .legacyHitbox(true)
                .attackHitboxMargin(0.1f)
                .disableOffhand(true)
                .restrictSprintSneak(true)
                .restrictSprintUse(true)
                .restrictSwimSpeed(true)
                .blockPlaceReach(4.5) // 1.8 survival block reach; caps modern sneak-bridge over-reach (paired with legacyHitbox's 1.8 eye)
                .legacyFluids(true)   // Animatium clients only: 1.8 water/lava movement (also suppresses the modern swim state)
                .disableElytraFlight(true) // Animatium clients only: no elytra gliding (1.8 has no elytra)
                .oldFlight(true)           // Animatium clients only: 1.8 creative/spectator flight (sneak slows horizontal)
                .leftClickItemUsage(true)  // Animatium clients only: can use an item while mining (1.8; modern blocks use-item mid-destroy)
                .disableAutoSneak(true)    // Animatium clients only: no auto-crouch-to-fit under a low ceiling (1.8 sneak is shift-only)
                .oldPhysics(true)          // Animatium clients only: 1.8 movement/block physics (momentum threshold, no bed bounce, no honey/bubble)
                .disableEntityPush(true)   // Animatium clients only: not shoved by entity collision (preference; pair w/ server-side push disable)
                .oldPlacement(true)        // Animatium clients only: 1.8 placement (no same-tick break+place refill / floating block)
                .nativeShortVelocity(true) // Animatium clients only: byte-exact 1.8 velocity wire (gated on the advertised decoder)
                .removeAttackCooldown(true) // server-side (any client): no modern attack cooldown / crosshair indicator (1.8 full hits)
                .build();
    }

    /**
     * The inverse of {@link #config()} - every cross-version knob OFF (vanilla 26.1), for a {@code /compat} toggle. Each
     * boolean knob is {@code false} and the pose set is empty so {@code PlayerConfigApplier} actively resets them (it only
     * writes non-null fields). {@code attackHitboxMargin}/{@code blockPlaceReach} stay null - the layered apply can't null
     * them, so a caller clears those (and restores {@code ATTACK_SPEED}) directly.
     */
    public static CompatConfig off() {
        return CompatConfig.builder()
                .disabledPoses()
                .restrictMovement(false)
                .legacyHitbox(false)
                .disableOffhand(false)
                .restrictSprintSneak(false)
                .restrictSprintUse(false)
                .restrictSwimSpeed(false)
                .legacyFluids(false)
                .disableElytraFlight(false)
                .oldFlight(false)
                .leftClickItemUsage(false)
                .disableAutoSneak(false)
                .oldPhysics(false)
                .disableEntityPush(false)
                .oldPlacement(false)
                .nativeShortVelocity(false)
                .removeAttackCooldown(false)
                .build();
    }
}
