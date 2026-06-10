package io.github.term4.minestommechanics.mechanics.knockback;

import net.minestom.server.coordinate.Vec;
import org.jetbrains.annotations.Nullable;

/**
 * A pluggable knockback transform. After the base/extra/friction/bounds pipeline has produced the final
 * knockback vector (blocks/tick, i.e. the client-decoded packet units), each configured component is applied
 * in order and may return a replacement vector - add a term ({@code kb.add(...)}), scale it (range reduction),
 * snap it, etc. Returning {@code null} leaves the vector unchanged for that hit, so a component fully
 * self-gates (decides whether <em>and</em> how it applies) from the
 * {@link KnockbackConfigResolver.KnockbackContext} (attacker/target, cause, services, ...).
 *
 * <p>Unlike the linear per-axis friction term, a component can apply non-linear logic (e.g. snapping to the
 * dominant cardinal axis, or distance-based scaling), which the base pipeline cannot express.
 */
@FunctionalInterface
public interface KnockbackComponent {

    /** The transformed knockback (blocks/tick) given the current vector, or {@code null} to leave it unchanged. */
    @Nullable Vec apply(KnockbackConfigResolver.KnockbackContext ctx, Vec kb);
}
