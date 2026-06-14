package io.github.term4.minestommechanics.mechanics.damage;

import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable input describing a single instance of damage to apply. Mirrors the knockback
 * snapshot inputs but for damage.
 *
 * @param target  the entity taking damage
 * @param type    the damage type (melee, fire tick, fall, custom, etc.)
 * @param source  the entity or object responsible for the damage (attacker, projectile, etc.), if any
 * @param point   a relevant position for the damage (source position), if any
 * @param item    the item involved (melee weapon, later a projectile's bow), if any
 * @param detail  type-specific payload attached by the producer (e.g. the fall distance), if any
 * @param amount  optional amount override; {@code null} uses {@link DamageTypeConfig#baseAmount(io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext)}
 * @param config  optional per-snapshot config override
 */
public record DamageSnapshot(Entity target, DamageType type, @Nullable Entity source,
                             @Nullable Point point, @Nullable ItemStack item, @Nullable Object detail,
                             @Nullable Float amount, @Nullable DamageConfig config) {

    public static DamageSnapshot of(Entity target, DamageType type) {
        return new DamageSnapshot(target, type, null, null, null, null, null, null);
    }

    public DamageSnapshot withTarget(Entity e) { return new DamageSnapshot(e, type, source, point, item, detail, amount, config); }
    public DamageSnapshot withType(DamageType t) { return new DamageSnapshot(target, t, source, point, item, detail, amount, config); }
    public DamageSnapshot withSource(@Nullable Entity e) { return new DamageSnapshot(target, type, e, point, item, detail, amount, config); }
    public DamageSnapshot withPoint(@Nullable Point p) { return new DamageSnapshot(target, type, source, p, item, detail, amount, config); }
    public DamageSnapshot withItem(@Nullable ItemStack i) { return new DamageSnapshot(target, type, source, point, i, detail, amount, config); }
    public DamageSnapshot withDetail(@Nullable Object d) { return new DamageSnapshot(target, type, source, point, item, d, amount, config); }
    public DamageSnapshot withAmount(@Nullable Float a) { return new DamageSnapshot(target, type, source, point, item, detail, a, config); }
    public DamageSnapshot withConfig(@Nullable DamageConfig c) { return new DamageSnapshot(target, type, source, point, item, detail, amount, c); }

    public Builder toBuilder() { return new Builder(target, type, source, point, item, detail, amount, config); }

    public static final class Builder {
        private Entity target;
        private DamageType type;
        private @Nullable Entity source;
        private @Nullable Point point;
        private @Nullable ItemStack item;
        private @Nullable Object detail;
        private @Nullable Float amount;
        private @Nullable DamageConfig config;

        Builder(Entity target, DamageType type, @Nullable Entity source, @Nullable Point point,
                @Nullable ItemStack item, @Nullable Object detail,
                @Nullable Float amount, @Nullable DamageConfig config) {
            this.target = target;
            this.type = type;
            this.source = source;
            this.point = point;
            this.item = item;
            this.detail = detail;
            this.amount = amount;
            this.config = config;
        }

        public Builder target(Entity e) { this.target = e; return this; }
        public Builder type(DamageType t) { this.type = t; return this; }
        public Builder source(@Nullable Entity e) { this.source = e; return this; }
        public Builder point(@Nullable Point p) { this.point = p; return this; }
        public Builder item(@Nullable ItemStack i) { this.item = i; return this; }
        public Builder detail(@Nullable Object d) { this.detail = d; return this; }
        public Builder amount(@Nullable Float a) { this.amount = a; return this; }
        public Builder config(@Nullable DamageConfig c) { this.config = c; return this; }

        public DamageSnapshot build() {
            return new DamageSnapshot(target, type, source, point, item, detail, amount, config);
        }
    }
}
