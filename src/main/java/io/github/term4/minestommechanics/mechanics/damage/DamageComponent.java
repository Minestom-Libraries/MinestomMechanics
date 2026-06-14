package io.github.term4.minestommechanics.mechanics.damage;

import io.github.term4.minestommechanics.api.event.DamageEvent;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import org.jetbrains.annotations.Nullable;

/**
 * A pluggable damage transform applied after the {@link DamageEvent} fires. Each configured component
 * runs in order and may return a replacement amount (e.g. zero out overdamage when the melee weapon
 * matches the opening hit). Returning {@code null} leaves the amount unchanged for that step, so a
 * component self-gates from the {@link DamageContext} and {@link DamageEvent}.
 */
@FunctionalInterface
public interface DamageComponent {

    /**
     * @param overdamage {@code true} when transforming the invul-window replacement delta;
     *                   {@code false} for a fresh hit
     * @return replacement amount, or {@code null} to leave unchanged
     */
    @Nullable Float apply(DamageContext ctx, DamageEvent event, float amount, boolean overdamage);
}
