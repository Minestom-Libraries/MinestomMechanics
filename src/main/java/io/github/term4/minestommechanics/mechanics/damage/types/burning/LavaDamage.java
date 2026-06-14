package io.github.term4.minestommechanics.mechanics.damage.types.burning;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import net.kyori.adventure.key.Key;

/**
 * Lava damage ({@code minecraft:lava}). Vanilla 1.8: 4.0 damage attempted every tick while the
 * bounding box overlaps lava (the invul window gates the cadence), igniting the entity for
 * 300 fire ticks unless wet; lava contact also halves accumulated fall distance (handled by the
 * fall producer's own scan). Self-driven via the shared {@link BurningTicker}; tunables come from
 * the active {@code DamageConfig} ({@link BurningConfig} registered via {@code typeConfigs(...)}),
 * falling back to {@link #defaultConfig()}.
 */
public final class LavaDamage extends DamageType {

    public static final Key KEY = Key.key("minecraft:lava");
    public static final LavaDamage INSTANCE = new LavaDamage();

    private LavaDamage() {
        super(KEY, "Lava", VanillaTypes.LAVA, BurningConfig.builder().key(KEY).build());
    }

    @Override
    public void enable(DamageSystem system, MinestomMechanics mm) {
        BurningTicker.activate(KEY, system, mm);
    }

    @Override
    public void disable() {
        BurningTicker.deactivate(KEY);
    }
}
