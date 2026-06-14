package io.github.term4.minestommechanics.mechanics.projectile.types;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base for a hand-thrown item projectile (snowball, egg, ender pearl): pressing-use the {@link #material} launches
 * one and consumes it (unless creative). Subclasses add only their flight entity (via
 * {@link #createEntity}) and impact behavior; the throw/consume wiring lives here. Mirrors the old impl's single
 * "throwable launcher" - the item causes the server to spawn the entity.
 */
public abstract class ThrowableItemType extends ProjectileType {

    private final Material material;
    private @Nullable EventNode<@NotNull Event> node;
    private @Nullable ProjectileSystem system;

    protected ThrowableItemType(Key key, String name, EntityType entityType, Material material) {
        super(key, name, entityType);
        this.material = material;
    }

    /** The item that throws this projectile. */
    public Material material() { return material; }

    @Override
    public void enable(ProjectileSystem system, MinestomMechanics mm) {
        this.system = system;
        EventNode<@NotNull Event> n = EventNode.all("mm:" + key().value());
        n.addListener(PlayerUseItemEvent.class, e -> {
            if (e.getItemStack().material() != material) return;
            Player p = e.getPlayer();
            system.launch(ProjectileSnapshot.of(p, this).withItem(e.getItemStack()));
            if (p.getGameMode() != GameMode.CREATIVE) {
                ItemStack held = e.getItemStack();
                p.setItemInHand(e.getHand(), held.withAmount(held.amount() - 1));
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
}
