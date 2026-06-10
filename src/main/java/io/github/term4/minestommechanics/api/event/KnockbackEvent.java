package io.github.term4.minestommechanics.api.event;

import io.github.term4.minestommechanics.mechanics.Cause;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfigResolver;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSnapshot;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.CancellableEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Public API for inspecting and modifying a knockback instance before it is applied. Wraps the
 * {@link KnockbackSnapshot}: an immutable original {@link #snapshot()} plus a mutable
 * {@link #finalSnap()} used for calculation, with optional {@link #velocity(Vec)} /
 * {@link #direction(Vec)} overrides that bypass or steer the calculator. Per-hit config changes go
 * through {@link #config(KnockbackConfig)}; {@link #resolvedConfig()} previews the effective values.
 */
public final class KnockbackEvent implements CancellableEvent {

    private final KnockbackSnapshot snap;
    private final Function<KnockbackSnapshot, KnockbackConfigResolver.ResolvedKnockbackConfig> resolver;
    private @Nullable KnockbackSnapshot finalSnap;
    private @Nullable Vec velocity;
    private @Nullable Vec direction;

    private boolean cancelled;

    public KnockbackEvent(KnockbackSnapshot snap,
                          Function<KnockbackSnapshot, KnockbackConfigResolver.ResolvedKnockbackConfig> resolver) {
        this.snap = snap;
        this.resolver = resolver;
    }

    /** Original knockback data (immutable). */
    public KnockbackSnapshot snapshot() { return snap; }

    /**
     * Snapshot used in knockback calculation.
     * Set via {@code event.finalSnap(event.snapshot().toBuilder().target(x).build())}.
     */
    public KnockbackSnapshot finalSnap() { return finalSnap != null ? finalSnap : snap; }
    public void finalSnap(KnockbackSnapshot snap) { this.finalSnap = snap; }

    /** Knockback config used for calculation ({@code null} = the system config). */
    public @Nullable KnockbackConfig config() { return finalSnap().config(); }

    /** Replaces the config used for calculation for this hit (sugar for rebuilding {@link #finalSnap()}). */
    public void config(@Nullable KnockbackConfig config) { finalSnap(finalSnap().withConfig(config)); }

    /**
     * The effective plain values this hit will be calculated with: the {@link #config() current config} (else the
     * system config) merged over the calculator defaults and resolved against this hit. Re-resolved from the
     * <em>current</em> {@link #finalSnap()} on every call - cheap - so it always reflects listener changes
     * ({@link #config(KnockbackConfig)} etc.) and always matches what the calculator will actually use.
     */
    public KnockbackConfigResolver.ResolvedKnockbackConfig resolvedConfig() { return resolver.apply(finalSnap()); }

    /** Knockback cause. Override by setting {@link #finalSnap(KnockbackSnapshot)} with a new cause. */
    public Cause cause() { return finalSnap().cause(); }

    /** If set, used instead of running the calculator. */
    public @Nullable Vec velocity() { return velocity; }
    public void velocity(@Nullable Vec velocity) { this.velocity = velocity; }

    /** If set, overrides the computed horizontal (xz) knockback direction (keeps the computed magnitude). */
    public @Nullable Vec direction() { return direction; }
    public void direction(@Nullable Vec direction) { this.direction = direction; }

    // delegating accessors
    public @Nullable Entity source() { return finalSnap().source(); }
    public @Nullable Entity target() { return finalSnap().target(); }

    /** Cancel the knockback. */
    public void cancel() { setCancelled(true); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
