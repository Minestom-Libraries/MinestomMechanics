package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ManagedProjectile;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base for a projectile type registered in the {@code ProjectileSystem} (the analog of {@code DamageType}).
 * Identifies a projectile (snowball, arrow, ...), maps to a Minestom {@link EntityType}, optionally carries
 * intrinsic {@link #defaultConfig() defaults} (most types are config-free - their tuning lives in the preset's
 * {@code ProjectileConfig}), and - for self-driven types - wires its launch trigger in {@link #enable}
 * (e.g. a snowball listens for the item use and calls {@link ProjectileSystem#launch}).
 *
 * <p>{@link #createEntity} produces the flying entity; the default is a generic {@link ManagedProjectile}
 * (knockback + damage on hit from the resolved config). Subclasses with extra behavior (arrow pickup/stick,
 * bobber hooking) override it. The launcher stamps the entity (bounding box, aerodynamics, sync, velocity).
 */
public abstract class ProjectileType {

    /** Empty intrinsic config: a config-free type carries no tuning - it all comes from the active
     *  {@code ProjectileConfig} (generic defaults + per-type override). */
    private static final ProjectileTypeConfig NO_DEFAULTS = ProjectileTypeConfig.builder().build();

    private final Key key;
    private final String name;
    private final EntityType entityType;
    private final ProjectileTypeConfig defaultConfig;

    /**
     * Config-free type: identity + behavior only, all tuning lives in the preset's {@code ProjectileConfig}
     * (its generic defaults plus this type's per-type entry). Preferred for built-in and custom types alike.
     */
    protected ProjectileType(Key key, String name, EntityType entityType) {
        this(key, name, entityType, NO_DEFAULTS);
    }

    /**
     * Type with intrinsic defaults baked in (for a type that ships its own sensible tuning); a preset's generic
     * defaults and per-type override still layer on top per the resolver chain.
     */
    protected ProjectileType(Key key, String name, EntityType entityType, ProjectileTypeConfig defaultConfig) {
        this.key = key;
        this.name = name;
        this.entityType = entityType;
        this.defaultConfig = defaultConfig;
    }

    public Key key() { return key; }
    public String name() { return name; }
    public EntityType entityType() { return entityType; }
    public @NotNull ProjectileTypeConfig defaultConfig() { return defaultConfig; }

    /**
     * Creates the flying entity for a launch. {@code effectiveConfig} is the merged per-type config (the hit knobs
     * resolve from it at impact). Default: a generic {@link ManagedProjectile}.
     */
    public ProjectileEntity createEntity(@Nullable Entity shooter, ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        return new ManagedProjectile(shooter, entityType, snap, effectiveConfig);
    }

    /** Wires this type's launch trigger (item use, etc.) and emits snapshots through {@code system}. No-op by default. */
    public void enable(ProjectileSystem system, MinestomMechanics mm) {}

    /** Tears down anything registered in {@link #enable}. No-op by default. */
    public void disable() {}

    @Override public String toString() { return "ProjectileType(" + key.asString() + ")"; }
}
