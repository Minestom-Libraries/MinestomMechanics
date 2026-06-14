package io.github.term4.minestommechanics.mechanics.damage;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.BurningDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.InFireDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.burning.LavaDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.cactus.CactusDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.fall.FallDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.generic.GenericDamage;
import io.github.term4.minestommechanics.mechanics.damage.types.playerattack.PlayerAttack;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of {@link DamageType}s that feed the {@link DamageSystem}. A user registers a type,
 * enables it, and it "just works". One-off types (e.g. melee) carry data and are driven externally;
 * self-driven types (fire, fall, custom) override {@link DamageType#enable} / {@link DamageType#disable}
 * to emit snapshots while enabled.
 */
public final class DamageTypeRegistry {

    private static final class Entry {
        final DamageType type;
        boolean enabled;
        Entry(DamageType type) {
            this.type = type;
        }
    }

    private final DamageSystem system;
    private final MinestomMechanics mm;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public DamageTypeRegistry(DamageSystem system, MinestomMechanics mm) {
        this.system = system;
        this.mm = mm;
    }

    /** Register a damage type, keyed by its {@link DamageType#key()}. */
    public DamageTypeRegistry register(DamageType type) {
        entries.put(type.key(), new Entry(type));
        return this;
    }

    /** Enable a registered type, starting its self-driven behavior (if any). No-op if already enabled. */
    public void enable(Key key) {
        Entry e = entries.get(key);
        if (e == null) throw new IllegalArgumentException("No damage type registered for " + key.asString());
        if (e.enabled) return;
        e.enabled = true;
        e.type.enable(system, mm);
    }

    /** Disable a registered type, tearing down its self-driven behavior (if any). No-op if not enabled. */
    public void disable(Key key) {
        Entry e = entries.get(key);
        if (e == null) return;
        if (!e.enabled) return;
        e.enabled = false;
        e.type.disable();
    }

    public boolean isEnabled(Key key) {
        Entry e = entries.get(key);
        return e != null && e.enabled;
    }

    public @Nullable DamageType get(Key key) {
        Entry e = entries.get(key);
        return e != null ? e.type : null;
    }

    /** Default config for a registered type, or {@code null} if not registered. */
    public @Nullable DamageTypeConfig config(Key key) {
        Entry e = entries.get(key);
        return e != null ? e.type.defaultConfig() : null;
    }

    public boolean contains(Key key) {
        return entries.containsKey(key);
    }

    /** All registered damage types. */
    public Collection<DamageType> all() {
        return entries.values().stream().map(e -> e.type).collect(Collectors.toUnmodifiableList());
    }

    /**
     * Registers the built-in vanilla damage type definitions (data only, no producers enabled).
     * Mirrors the keys cached in {@link VanillaTypes}. Self-driven producers (fall, the burning
     * family, cactus) start via {@link #enable} - typically through
     * {@code DamageSystem.install(mm, cfg, types...)}.
     */
    public DamageTypeRegistry registerVanillaDefaults() {
        register(PlayerAttack.INSTANCE);
        register(GenericDamage.INSTANCE);
        register(io.github.term4.minestommechanics.mechanics.damage.types.projectile.ProjectileDamage.INSTANCE);
        register(FallDamage.INSTANCE);
        register(InFireDamage.INSTANCE);
        register(BurningDamage.INSTANCE);
        register(LavaDamage.INSTANCE);
        register(CactusDamage.INSTANCE);
        return this;
    }
}
