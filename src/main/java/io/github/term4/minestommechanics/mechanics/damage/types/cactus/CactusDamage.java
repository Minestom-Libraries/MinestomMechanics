package io.github.term4.minestommechanics.mechanics.damage.types.cactus;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.damage.DamageProducers;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.EnvironmentalTickProducer;
import io.github.term4.minestommechanics.tracking.EnvironmentalDamageTicker;
import io.github.term4.minestommechanics.util.BlockContact;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.server.instance.block.Block;

/**
 * Cactus contact damage ({@code minecraft:cactus}). Vanilla 1.8: 1.0 damage attempted every tick
 * while the bounding box intersects a cactus block's <em>collision shape</em> (not the full cell —
 * vanilla insets 1/16 on the sides and top). The invul window gates the felt cadence.
 * Self-driven via {@link EnvironmentalDamageTicker}. Tunables come from the active {@code DamageConfig}
 * ({@link DamageTypeConfig} registered via {@code typeConfigs(...)}), falling back to
 * {@link #defaultConfig()}.
 */
public final class CactusDamage extends DamageType implements EnvironmentalTickProducer {

    public static final Key KEY = Key.key("minecraft:cactus");
    public static final CactusDamage INSTANCE = new CactusDamage();

    private boolean registered;

    private CactusDamage() {
        super(KEY, "Cactus", VanillaTypes.CACTUS, DamageTypeConfig.builder(KEY).build());
    }

    @Override
    public void enable(DamageSystem system, MinestomMechanics mm) {
        EnvironmentalDamageTicker.instance().bind(system, mm);
        if (!registered) {
            EnvironmentalDamageTicker.instance().register(this);
            registered = true;
        }
    }

    @Override
    public void disable() {
        if (registered) {
            EnvironmentalDamageTicker.instance().unregister(this);
            registered = false;
        }
    }

    @Override
    public void tick(EntityTickEvent event, DamageSystem sys) {
        if (!(event.getEntity() instanceof LivingEntity living) || living.isDead()) return;
        if (DamageProducers.exempt(living)) return;
        if (living.getInstance() == null) return;
        if (!BlockContact.touchingShapes(living, b -> b.compare(Block.CACTUS))) return;

        DamageSnapshot snap = DamageSnapshot.of(living, this);
        DamageContext ctx = sys.contextFor(snap);
        if (!ctx.typeConfig().enabled(ctx)) return;
        if (DamageSystem.absorbedByWindow(living, ctx.baseAmount())) return;
        sys.apply(snap);
    }
}
