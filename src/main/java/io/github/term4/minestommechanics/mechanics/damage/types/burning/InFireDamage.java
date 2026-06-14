package io.github.term4.minestommechanics.mechanics.damage.types.burning;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import net.kyori.adventure.key.Key;

/**
 * Standing-in-fire damage ({@code minecraft:in_fire}). Vanilla 1.8: 1.0 damage attempted every tick
 * while the bounding box overlaps a fire block (the invul window gates the cadence), igniting the
 * entity for 160 fire ticks unless wet. Self-driven via the shared {@link BurningTicker}; tunables
 * come from the active {@code DamageConfig} ({@link BurningConfig} registered via
 * {@code typeConfigs(...)}), falling back to {@link #defaultConfig()}.
 */
public final class InFireDamage extends DamageType {

    public static final Key KEY = Key.key("minecraft:in_fire");
    public static final InFireDamage INSTANCE = new InFireDamage();

    private InFireDamage() {
        super(KEY, "In Fire", VanillaTypes.IN_FIRE, BurningConfig.builder().key(KEY).build());
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
