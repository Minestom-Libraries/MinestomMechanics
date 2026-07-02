package io.github.term4.minestommechanics.mechanics.consumable.catalog;

import io.github.term4.minestommechanics.mechanics.attribute.catalog.VanillaPotions;
import io.github.term4.minestommechanics.mechanics.consumable.Consumable;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableBehavior;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableConfigResolver.ConsumableContext;
import io.github.term4.minestommechanics.mechanics.damage.types.magic.HealOrHarm;
import io.github.term4.minestommechanics.mechanics.hunger.HungerSystem;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemAnimation;
import net.minestom.server.item.Material;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;

/**
 * Vanilla consumable <em>type identities</em> (the golden apples) + the building blocks presets use for their
 * version-specific behavior. The types are version-agnostic (key + material); effects/food live in the preset's per-type
 * behavior, so the same registered apple gets 1.8 vs 26 effects by scope. Effects apply via {@code addEffect} (the attribute
 * potion lifecycle); food/saturation routes through {@link HungerSystem} (a no-op until that lands). Not a full vanilla food table - add more via the API.
 */
public final class VanillaConsumables {

    private VanillaConsumables() {}

    /** Golden apple type ({@code minecraft:golden_apple}); the preset supplies its effects/food. */
    public static final Consumable GOLDEN_APPLE = Consumable
            .builder(Key.key("minecraft:golden_apple"), Material.GOLDEN_APPLE).build();

    /** Enchanted ("notch") golden apple type ({@code minecraft:enchanted_golden_apple}); the preset supplies its effects/food. */
    public static final Consumable ENCHANTED_GOLDEN_APPLE = Consumable
            .builder(Key.key("minecraft:enchanted_golden_apple"), Material.ENCHANTED_GOLDEN_APPLE).build();

    /** Milk bucket ({@code minecraft:milk_bucket}): drinking clears all effects (version-invariant, so the behavior is on the type). */
    public static final Consumable MILK_BUCKET = Consumable
            .builder(Key.key("minecraft:milk_bucket"), Material.MILK_BUCKET)
            .animation(ItemAnimation.DRINK)
            .behavior(clearEffects())
            .build();

    /** Drinkable potion ({@code minecraft:potion}): applies the item's {@code potion_contents} effects (the same payload tipped arrows resolve), leaving a glass bottle. */
    public static final Consumable POTION = Consumable
            .builder(Key.key("minecraft:potion"), Material.POTION)
            .animation(ItemAnimation.DRINK)
            .behavior(drinkPotion())
            .remainder(Material.GLASS_BOTTLE)
            .build();

    /** The vanilla consumable types to register on the system ({@code ConsumableSystem.install(mm, cfg, VanillaConsumables.types())}). */
    public static Consumable[] types() {
        return new Consumable[]{GOLDEN_APPLE, ENCHANTED_GOLDEN_APPLE, MILK_BUCKET, POTION};
    }

    /** One potion effect for {@link #effectFood}: {@code level} is 1-based (level I = amplifier 0), {@code ticks} the duration. */
    public static Effect eff(PotionEffect id, int level, int ticks) { return new Effect(id, (byte) (level - 1), ticks); }

    /** A resolved potion effect a consumable applies on finish. */
    public record Effect(PotionEffect id, byte amplifier, int ticks) {}

    /**
     * A {@link ConsumableBehavior} that, on finish, restores {@code nutrition} food + {@code saturation} (through the
     * {@link HungerSystem}) and applies each {@link Effect} (particles + icon). The building block for food/golden-apple behaviors.
     */
    public static ConsumableBehavior effectFood(int nutrition, float saturation, Effect... effects) {
        return new ConsumableBehavior() {
            @Override public void onFinish(ConsumableContext ctx) {
                Player u = ctx.user();
                byte flags = ctx.particles().potionFlags();
                for (Effect e : effects) VanillaPotions.addEffect(u, new Potion(e.id(), e.amplifier(), e.ticks(), flags));
                HungerSystem hunger = ctx.services() != null ? ctx.services().hunger() : null;
                if (hunger != null) hunger.restore(u, nutrition, saturation);
            }
        };
    }

    /** A {@link ConsumableBehavior} that clears all of the user's active effects on finish (milk). */
    public static ConsumableBehavior clearEffects() {
        return new ConsumableBehavior() {
            @Override public void onFinish(ConsumableContext ctx) { ctx.user().clearEffects(); }
        };
    }

    /**
     * A {@link ConsumableBehavior} that, on finish, applies the item's {@code potion_contents} - the base potion (via
     * {@link VanillaPotions}) + any custom effects - at full duration. Instant effects (healing/harming) apply through
     * {@link HealOrHarm} at intensity {@code 1.0} (vanilla drink); a water bottle is a no-op.
     */
    public static ConsumableBehavior drinkPotion() {
        return new ConsumableBehavior() {
            @Override public void onFinish(ConsumableContext ctx) {
                Player u = ctx.user();
                byte flags = ctx.particles().potionFlags();
                for (CustomPotionEffect e : VanillaPotions.payload(ctx.item())) {
                    if (HealOrHarm.apply(ctx.services(), u, u, null, e, 1.0)) continue;
                    VanillaPotions.addEffect(u, new Potion(e.id(), e.amplifier(), e.duration(), flags));
                }
            }
        };
    }
}
