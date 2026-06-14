package io.github.term4.minestommechanics.api.event;

import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.event.trait.CancellableEvent;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Public API for inspecting and modifying a damage instance before it is applied. Wraps the
 * {@link DamageSnapshot} and follows the same shape as {@link KnockbackEvent}: an immutable
 * original {@link #snapshot()} plus a mutable {@link #finalSnap()} used for application.
 */
public final class DamageEvent implements CancellableEvent {

    private final DamageSnapshot snap;
    private @Nullable DamageSnapshot finalSnap;

    private float amount;
    private final boolean invulnerable;
    private final int remainingInvul;
    private final float stored;
    private boolean bypassInvul;

    private boolean cancelled;

    public DamageEvent(DamageSnapshot snap, float amount) {
        this.snap = snap;
        this.amount = amount;
        this.invulnerable = DamageSystem.isInvulnerableToDamage(snap.target());
        this.remainingInvul = snap.target() instanceof LivingEntity le
                ? DamageSystem.remainingDamageInvulTicks(le) : 0;
        this.stored = snap.target() instanceof LivingEntity le
                ? DamageSystem.lastDamage(le) : 0f;
    }

    /** Original damage data (immutable). */
    public DamageSnapshot snapshot() { return snap; }

    /**
     * Snapshot used when applying damage.
     * Set via {@code event.finalSnap(event.snapshot().toBuilder().target(x).build())}.
     */
    public DamageSnapshot finalSnap() { return finalSnap != null ? finalSnap : snap; }
    public void finalSnap(DamageSnapshot snap) { this.finalSnap = snap; }

    /** Final damage amount to apply (mutable). */
    public float amount() { return amount; }
    public void amount(float amount) { this.amount = amount; }

    /** Damage config used for resolution ({@code null} = the system config). */
    public @Nullable DamageConfig config() { return finalSnap().config(); }

    /** Replaces the config used for resolution for this hit (sugar for rebuilding {@link #finalSnap()}). */
    public void config(@Nullable DamageConfig config) { finalSnap(finalSnap().withConfig(config)); }

    /** Whether the target was inside its damage-invul window when this hit arrived. */
    public boolean invulnerable() { return invulnerable; }
    /** Remaining ticks of the target's damage-invul window ({@code 0} when not invulnerable). */
    public int remainingInvul() { return remainingInvul; }

    /**
     * The "last damage" highwater stored on the target for the current invulnerability window: the
     * largest amount applied so far while invulnerable. Vanilla overdamage applies only the delta
     * {@code amount - stored} when positive. {@code 0} when the target is fresh.
     */
    public float stored() { return stored; }

    public boolean bypassInvul() { return bypassInvul; }
    /** Apply damage even if the target is invulnerable. */
    public void bypassInvul(boolean bypass) { this.bypassInvul = bypass; }

    // delegating accessors
    public DamageType type() { return finalSnap().type(); }
    public Entity target() { return finalSnap().target(); }
    public @Nullable Entity source() { return finalSnap().source(); }
    /** Item involved in the damage (melee weapon, later a projectile's bow), or {@code null}. */
    public @Nullable ItemStack item() { return finalSnap().item(); }
    /** Type-specific payload attached by the producer (e.g. the fall distance), or {@code null}. */
    public @Nullable Object detail() { return finalSnap().detail(); }

    /** Cancel the damage. */
    public void cancel() { setCancelled(true); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
