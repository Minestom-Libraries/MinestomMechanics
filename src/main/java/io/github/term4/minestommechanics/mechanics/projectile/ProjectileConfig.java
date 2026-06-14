package io.github.term4.minestommechanics.mechanics.projectile;

import io.github.term4.minestommechanics.config.Config;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfigResolver.ProjectileContext;
import io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileTypeConfig;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Immutable projectile config: a generic {@link #defaults} base plus per-type {@link ProjectileTypeConfig}
 * overrides keyed by type key. Mirrors {@code DamageConfig} - presence in {@link #typeConfigs} is what enables a
 * type at install (see {@code ProjectileSystem.install}). Resolution layers per-type override -&gt; {@link #defaults}
 * -&gt; the type's intrinsic {@link io.github.term4.minestommechanics.mechanics.projectile.types.ProjectileType#defaultConfig()}
 * -&gt; hard fallbacks, so a preset keeps the shared projectile baseline once in {@code defaults} and each type entry
 * only restates its deltas (or is empty, just to enable the type). The global config is {@code defaults} + the map
 * + the {@code subConfig} overlay.
 */
public final class ProjectileConfig extends Config<ProjectileContext, ProjectileConfig> {

    /** Generic base config applied to every type unless the type's own entry overrides a knob ({@code null} = none). */
    public final @Nullable ProjectileTypeConfig defaults;
    /** Per-type config overrides, keyed by {@link ProjectileTypeConfig#key()}. */
    public final Map<Key, ProjectileTypeConfig> typeConfigs;

    private ProjectileConfig(Builder b) {
        super(b.subConfig);
        this.defaults = b.defaults;
        this.typeConfigs = Map.copyOf(b.typeConfigs);
    }

    /** The generic base config every type inherits, or {@code null} if none set. */
    public @Nullable ProjectileTypeConfig defaults() { return defaults; }

    /** Per-type config for {@code key}, or {@code null} if none registered. */
    public @Nullable ProjectileTypeConfig typeConfig(Key key) { return typeConfigs.get(key); }

    /** Merges this config over base (this config's generic defaults + per-type entries layered over base's). */
    public ProjectileConfig fromBase(ProjectileConfig base) {
        Map<Key, ProjectileTypeConfig> merged = new LinkedHashMap<>(base.typeConfigs);
        merged.putAll(typeConfigs);
        ProjectileTypeConfig mergedDefaults = defaults == null ? base.defaults
                : base.defaults == null ? defaults : defaults.fromBase(base.defaults);
        return new Builder()
                .subConfig(subConfig != null ? subConfig : base.subConfig)
                .defaults(mergedDefaults)
                .typeConfigs(merged)
                .build();
    }

    public Builder toBuilder() { return new Builder(this); }
    public static Builder builder() { return new Builder(); }
    public static Builder builder(@Nullable ProjectileConfig base) { return base != null ? new Builder(base) : new Builder(); }

    public static final class Builder {
        private Function<ProjectileContext, ProjectileConfig> subConfig;
        private @Nullable ProjectileTypeConfig defaults;
        private final Map<Key, ProjectileTypeConfig> typeConfigs = new LinkedHashMap<>();

        Builder() {}
        Builder(ProjectileConfig c) { subConfig = c.subConfig; defaults = c.defaults; typeConfigs.putAll(c.typeConfigs); }

        public Builder subConfig(Function<ProjectileContext, ProjectileConfig> fn) { subConfig = fn; return this; }
        /** Sets the generic base config every type inherits (its knobs apply unless a per-type entry overrides them). */
        public Builder defaults(@Nullable ProjectileTypeConfig generic) { this.defaults = generic; return this; }

        /** Adds per-type config overrides, each keyed by its {@link ProjectileTypeConfig#key()}. */
        public Builder typeConfigs(ProjectileTypeConfig... cfgs) {
            for (ProjectileTypeConfig c : cfgs) typeConfigs.put(c.key(), c);
            return this;
        }

        Builder typeConfigs(Map<Key, ProjectileTypeConfig> cfgs) { typeConfigs.putAll(cfgs); return this; }

        public ProjectileConfig build() { return new ProjectileConfig(this); }
    }
}
