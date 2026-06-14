package io.github.term4.minestommechanics.mechanics.damage.types;

import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.registry.RegistryKey;

/**
 * Cache of the vanilla Minecraft damage-type registry keys we reference. These are Minestom
 * {@link DamageType} keys (not our {@code io...mechanics.damage.types.DamageType}); each of our
 * damage types passes one as its {@code minecraftType} for application and death messages.
 * Backed by Minestom's {@code DamageTypes} constants, so they are valid without server init.
 */
public final class VanillaTypes {

    private VanillaTypes() {}

    public static final RegistryKey<DamageType> PLAYER_ATTACK = DamageType.PLAYER_ATTACK;
    public static final RegistryKey<DamageType> GENERIC = DamageType.GENERIC;
    public static final RegistryKey<DamageType> FALL = DamageType.FALL;
    public static final RegistryKey<DamageType> IN_FIRE = DamageType.IN_FIRE;
    public static final RegistryKey<DamageType> ON_FIRE = DamageType.ON_FIRE;
    public static final RegistryKey<DamageType> LAVA = DamageType.LAVA;
    public static final RegistryKey<DamageType> CACTUS = DamageType.CACTUS;

}
