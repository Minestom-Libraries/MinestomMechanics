package test.presets;

import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.api.event.AttackEvent;
import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageComponent;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.playerattack.PlayerAttack;
import io.github.term4.minestommechanics.api.event.DamageEvent;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfigResolver;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackComponent;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.platform.player.PlayerConfig;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.tracking.VelocityConfig;
import io.github.term4.minestommechanics.tracking.VelocityContext;
import io.github.term4.minestommechanics.tracking.VelocityRule;
import io.github.term4.minestommechanics.util.Directions;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// TODO: Try with piecewise function of some sort

public final class Minemen {
    private Minemen() {}

    /** Returns AttackConfig based on Vanilla18, processed through the {@link #attackProcessor() Minemen ruleset}. */
    public static AttackConfig atk() {
        return AttackConfig.builder(Vanilla18.atk())
                .ruleset(attackProcessor())
                .build();
    }

    /**
     * Minemen attack processing: vanilla legacy combat behind a {@link #HIT_QUEUE_BUFFER 1-tick} hit queue
     * against the damage-invul window - a hit landing within the last tick of the target's window is buffered
     * (last-wins per target, collapsing high-ping bursts) and applied the moment the window expires, instead of
     * being eaten by it. TODO(swing-hits): arm-swing-animation hits (swing packet -> raytrace + obstacle check
     * -> emulated attack, hit-distance logging) belong here too, not in AttackConfig.
     */
    public static AttackEvent.AttackRule.Ruleset attackProcessor() {
        return services -> {
            ensureQueueTask();
            AttackEvent.AttackRule vanilla = Vanilla18.legacyAttack().create(services);
            return event -> {
                if (event.target() instanceof LivingEntity le) {
                    if (!event.bypassInvul()
                            && DamageSystem.isInvulnerableToDamage(le)
                            && DamageSystem.remainingDamageInvulTicks(le) <= HIT_QUEUE_BUFFER) {
                        pendingHit.put(le, new PendingHit(event, vanilla));
                        return;
                    }
                    // Drain an already-expired queued hit first so the older hit lands before this one.
                    flushPending(le);
                }
                vanilla.processAttack(event);
            };
        };
    }

    /** Buffer window (ticks): hits this close to the end of the damage-invul window queue instead of dropping. */
    private static final int HIT_QUEUE_BUFFER = 1;
    /** At most one pending buffered hit per target, applied as a fresh hit when its window expires. */
    private static final Map<LivingEntity, PendingHit> pendingHit = new ConcurrentHashMap<>();
    private static final AtomicBoolean queueTaskStarted = new AtomicBoolean();

    /** A buffered hit: the fired event plus the processing rule to apply it with on flush. */
    private record PendingHit(AttackEvent event, AttackEvent.AttackRule rule) {}

    private static void ensureQueueTask() {
        if (!queueTaskStarted.compareAndSet(false, true)) return;
        MinecraftServer.getSchedulerManager()
                .buildTask(Minemen::flushExpired)
                .repeat(TaskSchedule.tick(1))
                .schedule();
    }

    private static void flushExpired() {
        for (LivingEntity target : pendingHit.keySet()) {
            if (target.isRemoved()) pendingHit.remove(target);
            else flushPending(target);
        }
    }

    /** Applies the pending hit for {@code le} once its window has expired, claiming the slot atomically. */
    private static void flushPending(LivingEntity le) {
        PendingHit p = pendingHit.get(le);
        if (p == null) return;
        if (DamageSystem.isInvulnerableToDamage(le)) return; // window still open
        if (!pendingHit.remove(le, p)) return; // claimed elsewhere
        p.rule().processAttack(p.event());
    }

    /** Returns DamageConfig based on Vanilla18 with overdamage enabled and applied silently (no hurt animation). */
    public static DamageConfig dmg() {
        return DamageConfig.builder(Vanilla18.dmg())
                .overdamageSilent(true)
                // Minemen deals no knockback on generic damage ticks: the hurt broadcast is off entirely
                // (the only way to switch off an inherited hurtKnockback - merge semantics can't null it).
                .syncHurtVelocity(false)
                .addCustomComponent(Minemen::blockSameItemOverdamage)
                .build();
    }

    /**
     * Minemen overdamage rule: skip replacement when the incoming melee hit uses the same weapon
     * (material) as the hit that opened the invulnerability window.
     */
    @Nullable
    private static Float blockSameItemOverdamage(DamageContext ctx, DamageEvent event, float amount, boolean overdamage) {
        if (!overdamage || amount <= 0) return null;
        if (!PlayerAttack.KEY.equals(event.type().key())) return null;
        if (!(event.target() instanceof LivingEntity le)) return null;
        if (sameItem(event.item(), DamageSystem.openingHitItem(le))) return 0f;
        return null;
    }

    private static boolean sameItem(@Nullable ItemStack a, @Nullable ItemStack b) {
        boolean aFist = a == null || a.isAir();
        boolean bFist = b == null || b.isAir();
        if (aFist && bFist) return true;
        if (aFist != bFist) return false;
        return a.material().equals(b.material());
    }

    public static PlayerConfig player() {
        return PlayerConfig.builder(Vanilla18.player())
                .positionBroadcastInterval(1)
                .build();
    }

    /**
     * Minemen projectile config. Inherits the vanilla 1.8 baseline ({@link Vanilla18#projectileDefaults()} physics +
     * {@link Vanilla18#snowball()} damage/spawn) so projectile behavior is unchanged today. This is the seam to give
     * projectiles Minemen's feel - e.g. override the generic knockback for a cap-hold vertical so chained snowballs
     * don't sag like vanilla:
     * {@code .defaults(Vanilla18.projectileDefaults().toBuilder().knockback(projectileKb()).build())}.
     */
    public static ProjectileConfig projectiles() {
        return ProjectileConfig.builder(Vanilla18.projectiles()).build();
    }

    /**
     * Minemen knockback. Velocity is NOT pinned here - it reads from the scope, so pair this with
     * {@code MechanicsProfile.velocity(Minemen.velocity())} (the friction fold AND the custom components below
     * read the one scoped rule via {@code ctx.victimVelocity()}). Without a scoped velocity the fold + components
     * fall back to {@link VelocityRule#DEFAULT}, so melee and projectiles stay consistent either way.
     */
    public static KnockbackConfig kb() {
        return KnockbackConfig.builder(Vanilla18.kb())
                .sprintBuffer(SPRINT_BUFFER)
                .horizontal(0.5274)
                .vertical(VERTICAL_BASE)
                .extraHorizontal(0.3271)
                .extraVertical(0.0)
                .verticalBounds(0.050, VERTICAL_CAP)
                .yawWeight(0.5)
                .extraYawWeight(0.5)
                .frictionH(0.0)
                .frictionV(VERTICAL_FRIC)
                .addCustomComponent(Minemen::verticalLaunchHold)
                .addCustomComponent(Minemen::axialFriction)
                // Range reduction is the LAST stage - a final limit on everything above it. The fix for the
                // behind-hit crush was the RANGE_LIMIT_MIN floor (not reordering): without it the far-range
                // line fell toward 0 and re-capped a drag-reduced behind-hit to ~0-0.3; the floor pins it at
                // RANGE_LIMIT_MIN (~0.5674) instead.
                .addCustomComponent(Minemen::rangeReduction)
                .build();
    }

    /**
     * Vertical KB decay from a single integer N (= 7) and vanilla 1.8 physics:
     * {@code KB_Y = CAP + (v - v1)/(N*d)} with {@code v1 = -g*d = -0.0784} - the decay line extrapolates back
     * to the cap at the first gravity tick, so {@code frictionV = N*drag} and {@code base = CAP + gravity/N}.
     * N = 7 is the only integer reproducing the empirical wire-shorts (no-jump ticks 6-41, jump 13-19) exactly.
     */
    private static final int VERTICAL_DECAY_N = 7;
    private static final double VERTICAL_CAP = 0.3614;
    private static final double VERTICAL_BASE = VERTICAL_CAP + VelocityConfig.GRAVITY / VERTICAL_DECAY_N;   // 0.3728286
    private static final double VERTICAL_FRIC = VERTICAL_DECAY_N * VelocityConfig.DRAG_V;                   // 6.86

    /** Recent-sprint window (ticks) for both the sprint knockback and the axial drag's victim gate. */
    private static final int SPRINT_BUFFER = 8;
    /** Vanilla sprint-jump horizontal impulse (blocks/tick) - the victim's reconstructed sprint speed while sprinting. */
    private static final double SPRINT_JUMP_IMPULSE = 0.2;
    /** Axial-drag coefficient - this term's own "frictionH", separate from the config's: {@code 0.475 * 0.2 = 0.095}. */
    private static final double AXIAL_iFH = 0.475;
    /**
     * Victim velocity, used by both the friction term and the axial drag. Horizontal = {@link #sprintVel quantized
     * sprint reconstruction}; vertical = server-side air-tick gravity arc (ping-invariant - MMC's vertical KB is
     * identical across pings, so it cannot be client position-delta - the fall that drives friction-V). Horizontal
     * is inert for friction (Minemen's {@code frictionH = 0}); it rides along only as the axial drag's speed source.
     */
    private static final VelocityRule VICTIM_VEL = VelocityRule.split(Minemen::sprintVel,
            VelocityRule.simulated()); // vanilla defaults: PER_TICK vertical, launch offset -1

    /**
     * Minemen velocity tracking method ({@link #VICTIM_VEL}): split sprint-reconstruction horizontal +
     * server-arc vertical. Set on a {@code MechanicsProfile.velocity(...)} scope - the friction fold and the
     * axial-drag / cap-hold components then read it through {@code ctx.victimVelocity()}, so it is configured
     * once here, not pinned onto {@link #kb()}.
     */
    public static VelocityRule velocity() {
        return VICTIM_VEL;
    }

    /**
     * Quantized victim sprint velocity: the flat sprint-jump {@link #SPRINT_JUMP_IMPULSE} along the victim's facing
     * while its client was sprinting within {@link #SPRINT_BUFFER} ticks, else zero. The single place to fold in any
     * future per-victim sprint-speed modifier (speed effects, attribute scaling, ...).
     */
    private static Vec sprintVel(VelocityContext ctx) {
        return ctx.wasClientRecentlySprinting(SPRINT_BUFFER)
                ? Directions.fromYaw(ctx.entity().getPosition().yaw()).mul(SPRINT_JUMP_IMPULSE)
                : Vec.ZERO;
    }

    /** Cap-hold release threshold (b/t): the fold's vertical must fall below this before the cap releases. */
    private static final double VERTICAL_HOLD_RELEASE = -VelocityConfig.JUMP_VELOCITY; // -0.42: holds through air-tick 5, releases into decay at tick 6 (walk-off feeds v6)

    /**
     * Minemen vertical launch cap-hold: while the victim's launch arc is still rising / barely falling (the
     * {@link #VICTIM_VEL reconstructed} vertical velocity above {@link #VERTICAL_HOLD_RELEASE}), vertical knockback
     * is pinned to {@link #VERTICAL_CAP} instead of being sagged by the friction term - what makes a jump's cap
     * hold longer than a walk-off's. Releases into the normal {@code base + v/frictionV} decay once the fall
     * builds past the threshold.
     */
    @Nullable
    private static Vec verticalLaunchHold(KnockbackConfigResolver.KnockbackContext ctx, Vec kb) {
        double vy = ctx.victimVelocity().y();
        if (vy <= VERTICAL_HOLD_RELEASE) return null;
        return new Vec(kb.x(), VERTICAL_CAP, kb.z());
    }

    /**
     * Minemen axial sprint drag: a {@code +-(AXIAL_iFH * sprintSpeed)} push locked to one cardinal axis, summed onto
     * the final knockback - a drag opposing the victim's sprint momentum. It's snapped to an axis (unlike the linear
     * {@code frictionH} term), which is why it's its own {@link KnockbackComponent}. Today {@code 0.475 * 0.2 = 0.095},
     * but any sprint-speed change in {@link #VICTIM_VEL} scales it.
     *
     * <p><b>Gating.</b> Self-gated to a melee hit by a recently {@link SprintTracker#wasRecentlySprinting sprinting}
     * attacker; the victim-sprinting gate is folded into {@link #VICTIM_VEL} (zero speed -> no contribution). The
     * source/non-melee guards also cover the eager resolve pass (components run on every knockback, even fall/fire).
     *
     * <p><b>Direction.</b> The axis is the attacker's snapped facing (~the push axis on a hit), so it never lands on a
     * perpendicular. Only the sign flips, opposing the victim's sprint velocity along that axis: facing toward the
     * attacker adds ({@code ~0.8545 -> ~0.9495}), fleeing subtracts ({@code ~0.8545 -> ~0.7595}).
     */
    @Nullable
    private static Vec axialFriction(KnockbackConfigResolver.KnockbackContext ctx, Vec kb) {
        var snap = ctx.snap();
        Entity attacker = snap.source();
        if (attacker == null || !snap.melee()) return null;
        var tracker = ctx.services().sprintTracker();
        if (!SprintTracker.wasRecentlySprinting(tracker, attacker, SPRINT_BUFFER)) return null;     // sprint hit

        Vec vel = ctx.victimVelocity();
        double speed = Math.hypot(vel.x(), vel.z());
        if (speed <= 0) return null;

        var aPos = attacker.getPosition();

        // Using attacker yaw is technically lower resolution than using push, and more inconsistent at ~0, 90, etc, but i think
        // that's honestly more consistent with MineMen
        Vec axis = Directions.snapDominantAxis(Directions.fromYaw(aPos.yaw()));

        /*
        // Could also be this, they're nearly identical. The following method suffers less from knockback displacement

        var tPos = snap.target().getPosition();
        Vec push = new Vec(tPos.x() - aPos.x(), 0, tPos.z() - aPos.z()); // attacker -> target (base push dir)
        if (push.lengthSquared() < 1e-9) return null;
        Vec axis = Directions.snapDominantAxis(push);
         */

        double sign = vel.dot(axis) <= 0 ? 1.0 : -1.0;
        return kb.add(axis.mul(sign * AXIAL_iFH * speed));
    }

    /** Range limit line: max horizontal KB (b/t) = {@link #RANGE_LIMIT_BASE} - distance x {@link #RANGE_FACTOR}. */
    private static final double RANGE_LIMIT_BASE = 2.0;
    private static final double RANGE_FACTOR = 0.35;
    /** Floor of the range limit: no distance can cap horizontal KB below this (empirical; above the 0.5274 base - fine). */
    private static final double RANGE_LIMIT_MIN = 0.5674;

    /**
     * Minemen range reduction - a linear <em>limit</em> on knockback by distance, not a scale-down: the
     * horizontal magnitude is capped at {@code max(RANGE_LIMIT_BASE - distance * RANGE_FACTOR, RANGE_LIMIT_MIN)}
     * - i.e. {@code kb = min(max(line, kbMin), kb0)} - so close hits are untouched, far-reach hits cannot
     * exceed the line, and the {@link #RANGE_LIMIT_MIN} floor keeps long-range hits from being crushed toward
     * zero. Runs LAST (after {@link #axialFriction} and every other stage) - a final cap on the whole vector;
     * direction and vertical are preserved.
     */
    @Nullable
    private static Vec rangeReduction(KnockbackConfigResolver.KnockbackContext ctx, Vec kb) {
        var snap = ctx.snap();
        Entity attacker = snap.source();
        Entity target = snap.target();
        if (attacker == null || target == null || !snap.melee()) return null;

        var aPos = attacker.getPosition();
        var tPos = target.getPosition();
        double dist = Math.hypot(tPos.x() - aPos.x(), tPos.z() - aPos.z());
        double limit = Math.max(RANGE_LIMIT_BASE - dist * RANGE_FACTOR, RANGE_LIMIT_MIN);
        double hMag = Math.hypot(kb.x(), kb.z());
        if (hMag <= limit) return null; // under the line - untouched
        double s = limit / hMag;
        return new Vec(kb.x() * s, kb.y(), kb.z() * s);
    }

}