package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ArrowEntity;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.item.PlayerCancelItemUseEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Arrow projectile fired by a bow. The Arrow type IS the projectile (entity = {@link ArrowEntity}) AND wires the
 * bow draw trigger in {@link #enable}: releasing a drawn bow ({@link PlayerCancelItemUseEvent}) computes the draw
 * power ({@code (s^2 + 2s)/3}, capped 1; {@code < 0.1} cancels; {@code >= 1.0} = critical), consumes an arrow
 * (unless creative), and launches the {@link ArrowEntity} with that power. Config-free; tuning lives in
 * {@code Vanilla18.arrow()} (speed 3.0, gravity 0.05, velocity-based damage, stick-in-block). TODO: gate the draw on
 * having arrows, offhand-first arrow selection, Infinity/enchants.
 */
public final class Arrow extends ProjectileType {

    public static final Key KEY = Key.key("minecraft:arrow");
    public static final Arrow INSTANCE = new Arrow();

    /** Minimum draw power that fires (vanilla: {@code < 0.1} releases nothing). */
    private static final float MIN_POWER = 0.1f;

    private @Nullable EventNode<@NotNull Event> node;
    private @Nullable ProjectileSystem system;

    private Arrow() { super(KEY, "Arrow", EntityType.ARROW); }

    @Override
    public ProjectileEntity createEntity(@Nullable Entity shooter, ProjectileSnapshot snap, ProjectileTypeConfig effectiveConfig) {
        return new ArrowEntity(shooter, entityType(), snap, effectiveConfig);
    }

    @Override
    public void enable(ProjectileSystem system, MinestomMechanics mm) {
        this.system = system;
        EventNode<@NotNull Event> n = EventNode.all("mm:bow");
        n.addListener(PlayerCancelItemUseEvent.class, e -> {
            if (e.getItemStack().material() != Material.BOW) return;
            Player p = e.getPlayer();
            float power = drawPower(e.getUseDuration());
            if (power < MIN_POWER) return; // too short a draw - no shot
            boolean creative = p.getGameMode() == GameMode.CREATIVE;
            if (!creative && !consumeArrow(p)) return; // no arrow to fire
            ProjectileEntity proj = system.launch(ProjectileSnapshot.of(p, this).withPower(power).withItem(e.getItemStack()));
            if (proj instanceof ArrowEntity arrow) {
                arrow.setCritical(power >= 1f);
                // Survival shot -> ALLOWED (collector keeps the arrow); creative shot -> CREATIVE_ONLY (no item).
                arrow.setPickup(creative ? ArrowEntity.Pickup.CREATIVE_ONLY : ArrowEntity.Pickup.ALLOWED);
            }
        });
        system.node().addChild(n);
        node = n;
    }

    @Override
    public void disable() {
        if (system != null && node != null) system.node().removeChild(node);
        node = null;
    }

    /** Vanilla bow power curve: {@code f = ticks/20; (f*f + 2f)/3}, capped at 1. */
    private static float drawPower(long useDurationTicks) {
        float f = useDurationTicks / 20.0f;
        float power = (f * f + 2 * f) / 3.0f;
        return power > 1f ? 1f : power;
    }

    /** Removes one arrow from the player's inventory (first {@link Material#ARROW} found); false if none. */
    private static boolean consumeArrow(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItemStack(i);
            if (stack.material() == Material.ARROW) {
                inv.setItemStack(i, stack.withAmount(stack.amount() - 1));
                return true;
            }
        }
        return false;
    }
}
