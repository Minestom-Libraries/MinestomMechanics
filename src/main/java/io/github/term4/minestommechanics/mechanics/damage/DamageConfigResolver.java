package io.github.term4.minestommechanics.mechanics.damage;

import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.config.FieldValue;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Resolves DamageConfig with context into plain values. Mirrors KnockbackConfigResolver. */
public final class DamageConfigResolver {

    private DamageConfigResolver() {}

    public record DamageContext(DamageSnapshot snap, Services services) {
        public static DamageContext of(DamageSnapshot snap, Services services) {
            return new DamageContext(snap, services);
        }

        /** Item involved in the damage (melee weapon, later a projectile's bow), or {@code null}. */
        public @Nullable ItemStack item() { return snap.item(); }

        /** Type-specific payload attached by the producer (e.g. the fall distance), or {@code null}. */
        public @Nullable Object detail() { return snap.detail(); }

        /** The {@link #detail()} payload when it is an instance of {@code type}, else {@code null}. */
        public <T> @Nullable T detail(Class<T> type) {
            Object d = snap.detail();
            return type.isInstance(d) ? type.cast(d) : null;
        }

        /**
         * Effective per-type config for this damage: the active {@link DamageConfig}'s override for the
         * type (snapshot config override, else the installed damage config), else the type's
         * {@link io.github.term4.minestommechanics.mechanics.damage.types.DamageType#defaultConfig()}.
         */
        public DamageTypeConfig typeConfig() {
            DamageConfig cfg = snap.config();
            if (cfg == null && services != null && services.damage() != null) {
                cfg = services.damage().config();
            }
            DamageTypeConfig tc = cfg != null ? cfg.typeConfig(snap.type().key()) : null;
            DamageTypeConfig base = tc != null ? tc : snap.type().defaultConfig();
            // Context-aware overlay: subConfig.apply(ctx) layered over the selected type config.
            if (base.subConfig() != null) {
                DamageTypeConfig overlay = base.subConfig().apply(this);
                if (overlay != null) base = overlay.fromBase(base);
            }
            return base;
        }

        /** Base amount before modifiers: snapshot override, else the type's resolved base amount. */
        public float baseAmount() {
            if (snap.amount() != null) return snap.amount();
            Double base = typeConfig().baseAmount(this);
            return base != null ? base.floatValue() : 0f;
        }
    }

    public static ResolvedDamageConfig resolve(DamageConfig config, DamageContext ctx) {
        DamageConfig cfg = config;
        if (cfg.subConfig != null) {
            DamageConfig sub = cfg.subConfig.apply(ctx);
            if (sub != null) cfg = sub.fromBase(cfg);
        }

        Integer invulTicks = resolve(cfg.invulTicks, ctx);
        Boolean enableOverdamage = resolve(cfg.enableOverdamage, ctx);
        Boolean silent = resolve(cfg.silent, ctx);
        Boolean overdamageSilent = resolve(cfg.overdamageSilent, ctx);
        Boolean syncHurtVelocity = resolve(cfg.syncHurtVelocity, ctx);
        KnockbackConfig hurtKnockback = resolve(cfg.hurtKnockback, ctx);

        return new ResolvedDamageConfig(ctx.baseAmount(), invulTicks, enableOverdamage, silent, overdamageSilent, syncHurtVelocity, hurtKnockback);
    }

    private static <T> T resolve(@Nullable FieldValue<DamageContext, T> fv, DamageContext ctx) {
        return fv != null ? fv.resolve(ctx) : null;
    }

    /** Resolved config with plain values (nullable when unset). Used by DamageCalculator. */
    public record ResolvedDamageConfig(
            float baseAmount,
            @Nullable Integer invulTicks,
            @Nullable Boolean enableOverdamage,
            @Nullable Boolean silent,
            @Nullable Boolean overdamageSilent,
            @Nullable Boolean syncHurtVelocity,
            @Nullable KnockbackConfig hurtKnockback
    ) {}
}
