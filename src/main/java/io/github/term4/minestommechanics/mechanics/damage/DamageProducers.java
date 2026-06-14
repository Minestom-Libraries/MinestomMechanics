package io.github.term4.minestommechanics.mechanics.damage;

import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;

/** Shared guards for self-driven environmental damage producers. */
public final class DamageProducers {

    private DamageProducers() {}

    /** Creative, spectator, and flying players do not take environmental damage (vanilla). */
    public static boolean exempt(LivingEntity living) {
        if (!(living instanceof Player p)) return false;
        GameMode gm = p.getGameMode();
        return p.isFlying() || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
}
