package test.presets;

import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.api.event.AttackEvent;
import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.melee.MeleeDamage;
import io.github.term4.minestommechanics.api.event.DamageEvent;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfigResolver;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackComponent;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.platform.compatibility.CompatConfig;
import io.github.term4.minestommechanics.platform.player.PlayerConfig;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.tracking.motion.VelocityConfig;
import io.github.term4.minestommechanics.tracking.motion.VelocityContext;
import io.github.term4.minestommechanics.tracking.motion.VelocityRule;
import io.github.term4.minestommechanics.util.tick.TickPhase;
import io.github.term4.minestommechanics.util.tick.TickSystem;
import io.github.term4.minestommechanics.util.Directions;
import io.github.term4.minestommechanics.util.tick.TickScaler;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>MMC 1.8</b> preset - replicates MineMenClub's 1.8 PvP feel (the {@code mmc18} distinction is deliberate: a modern
 * MineMen server gets its own preset later). Built on the {@link Vanilla18} 1.8 baseline with MMC-specific deltas:
 * the {@link #attackProcessor() hit-queue ruleset}, silent overdamage, and the custom {@link #velocity() velocity rule}
 * + knockback components below.
 */
public final class mmc18 {
    private mmc18() {}

    /** Returns AttackConfig based on Vanilla18, processed through the {@link #attackProcessor() mmc18 ruleset}. */
    public static AttackConfig atk() {
        return AttackConfig.builder(Vanilla18.atk())
                .ruleset(attackProcessor())
                .fullHitScale(1.0) // mmc18: a landed hit does NOT slow the attacker's tracked velocity
                .build();
    }

    /**
     * mmc18 attack processing: vanilla legacy combat behind a {@link #HIT_QUEUE_BUFFER 1-tick} hit queue
     * against the damage-invul window - a hit landing within the last tick of the target's window is buffered
     * (last-wins per target, collapsing high-ping bursts) and applied the moment the window expires, instead of
     * being eaten by it. TODO(swing-hits): arm-swing-animation hits (swing packet -> raytrace + obstacle check
     * -> emulated attack, hit-distance logging) belong here too, not in AttackConfig.
     */
    public static AttackEvent.AttackRule.Ruleset attackProcessor() {
        return services -> {
            ensureFlushListener();
            AttackEvent.AttackRule vanilla = Vanilla18.legacyAttack().create(services);
            return event -> {
                if (event.target() instanceof LivingEntity le) {
                    // the buffer is a vanilla-tick window, so scale it to live TPS (identity at 20) to keep the same real-time slop
                    if (DamageSystem.isInvulnerableToDamage(le)
                            && DamageSystem.remainingDamageInvul(le) <= TickScaler.duration(HIT_QUEUE_BUFFER, services.profiles().scalingFor(le), DamageSystem.KEY)) {
                        pendingHit.put(le, PendingHit.capture(event, vanilla));
                        return;
                    }
                    // Drain an already-expired queued hit first so the older hit lands before this one.
                    if (flushPending(le)) return;
                }
                vanilla.processAttack(event);
            };
        };
    }

    /** Buffer window (ticks): hits this close to the end of the damage-invul window queue instead of dropping. */
    private static final int HIT_QUEUE_BUFFER = 1;
    /** At most one pending buffered hit per target, applied as a fresh hit when its window expires. */
    private static final Map<LivingEntity, PendingHit> pendingHit = new ConcurrentHashMap<>();
    private static final AtomicBoolean flushListenerStarted = new AtomicBoolean();

    /** A buffered hit: the fired event plus the processing rule to apply it with on flush. */
    private record PendingHit(AttackEvent event, AttackEvent.AttackRule rule) {
        private static PendingHit capture(AttackEvent event, AttackEvent.AttackRule rule) {
            // AttackEvent reads crit/item lazily; freeze them so the delayed hit is the queued swing, not a later state.
            event.overrideCritical(event.critical());
            event.overrideItem(event.item());
            return new PendingHit(event, rule);
        }
    }

    /**
     * Queue expiry runs as a {@link TickPhase#PRE_DISPATCH} {@link TickSystem} tickable - right after the instance's
     * combat tick advances, strictly before the dispatcher processes that tick's attack packets. Replaces the old
     * global-scheduler repeat task, which ran on a different phase than the packet path and let a flush-opened window be
     * judged a physical tick early. Documented order per tick: advance clock -> flush expired -> attacks.
     */
    private static void ensureFlushListener() {
        if (!flushListenerStarted.compareAndSet(false, true)) return;
        TickSystem.register(TickPhase.PRE_DISPATCH, ctx -> flushExpired(ctx.instance()));
    }

    private static void flushExpired(Instance instance) {
        for (LivingEntity target : pendingHit.keySet()) {
            if (target.isRemoved()) pendingHit.remove(target);
            else if (target.getInstance() == instance) flushPending(target);
        }
    }

    /** Applies the pending hit for {@code le} once its window has expired, claiming the slot atomically. */
    private static boolean flushPending(LivingEntity le) {
        PendingHit p = pendingHit.get(le);
        if (p == null) return false;
        if (DamageSystem.isInvulnerableToDamage(le)) return false; // window still open
        if (!pendingHit.remove(le, p)) return false; // claimed elsewhere
        p.rule().processAttack(p.event());
        return true;
    }

    /** Returns DamageConfig based on Vanilla18 with overdamage enabled and applied silently (no hurt animation). */
    public static DamageConfig dmg() {
        return DamageConfig.builder(Vanilla18.dmg())
                .overdamageSilent(true)
                // mmc18 deals no knockback on generic damage ticks: the hurt broadcast is off entirely
                // (the only way to switch off an inherited hurtKnockback - merge semantics can't null it).
                .syncHurtVelocity(false)
                .addCustomComponent(mmc18::blockSameItemOverdamage)
                .build();
    }

    /**
     * mmc18 overdamage rule: skip replacement when the incoming melee hit uses the same weapon
     * (material) as the hit that opened the invulnerability window.
     */
    @Nullable
    private static Float blockSameItemOverdamage(DamageContext ctx, DamageEvent event, float amount, boolean overdamage) {
        if (!overdamage || amount <= 0) return null;
        if (!MeleeDamage.KEY.equals(event.type().key())) return null;
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
     * mmc18 cross-version compat: serve 1.8 poses/movement to modern clients. {@code disabledPoses} forces {@code SWIMMING}
     * (swim + the squeeze-to-fit crawl) and {@code FALL_FLYING} (elytra) back to {@code STANDING} server-side (the pose
     * visual stays client-local). {@code restrictMovement} then blocks moving where the server hitbox can't fit, so a
     * client rendering itself crawling still can't traverse the gap. {@code legacyHitbox} keeps the server box at 1.8 dims
     * (no crouch shrink) + 1.8 eye heights, which also makes {@code restrictMovement} block the 1.5-block sneak gap.
     */
    public static CompatConfig compat() {
        return CompatConfig.builder()
                .disabledPoses(EntityPose.SWIMMING, EntityPose.FALL_FLYING)
                .restrictMovement(true)
                .legacyHitbox(true)
                .build();
    }

    // TODO: Update stub with actual minemen projectiles
    /**
     * mmc18 projectile config. Inherits the vanilla 1.8 baseline ({@link Vanilla18#projectileDefaults()} physics +
     * {@link Vanilla18#snowball()} damage/spawn) so projectile behavior is unchanged today. This is the seam to give
     * projectiles mmc18's feel - e.g. override the generic knockback for a cap-hold vertical so chained snowballs
     * don't sag like vanilla:
     * {@code .defaults(Vanilla18.projectileDefaults().toBuilder().knockback(projectileKb()).build())}.
     */
    public static ProjectileConfig projectiles() {
        return ProjectileConfig.builder(Vanilla18.projectiles()).build();
    }

    /**
     * mmc18 knockback. Velocity is NOT pinned here - it reads from the scope, so pair this with
     * {@code MechanicsProfile.velocity(mmc18.velocity())} (the friction fold AND the custom components below
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
                .addCustomComponent(mmc18::verticalLaunchHold)
                .addCustomComponent(mmc18::axialFriction)
                .addCustomComponent(mmc18::rangeReduction)
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
    private static final int SPRINT_BUFFER = 5;
    /** Vanilla sprint-jump horizontal impulse (blocks/tick) - the victim's reconstructed sprint speed while sprinting. */
    private static final double SPRINT_JUMP_IMPULSE = 0.2;
    /** Axial-drag coefficient - this term's own "frictionH", separate from the config's: {@code 0.475 * 0.2 = 0.095}. */
    private static final double AXIAL_iFH = 0.475;
    /**
     * mmc18's velocity rule - the <em>general</em> velocity for every context (the KB friction fold, the axial drag,
     * projectile momentum), read everywhere via {@code ctx.victimVelocity()}. A custom {@link VelocityRule#split split}:
     * horizontal = {@link #sprintVel quantized sprint reconstruction} (inert for friction - mmc18's {@code frictionH = 0} -
     * it only feeds the axial drag's speed); vertical = the server-arc gravity sim (ping-invariant, since MMC's vertical KB
     * is identical across pings).
     *
     * <p><b>Fluid OFF:</b> {@code fluidPhysics(false)} skips the water/lava drag + buoyancy (and, since the current only
     * fires in water, the flow too), so a victim in water folds the same KB as in air. Climb (ladders/vines) and web stay
     * ON. The gates read these off the split's vertical component (see {@link VelocityRule.Split} /
     * {@link VelocityRule#reconstructionConfig()}).
     */
    private static final VelocityRule VELOCITY = VelocityRule.split(mmc18::sprintVel,
            VelocityRule.simulated(VelocityConfig.builder()
                    .fluidPhysics(false)
                    .build()));

    /** mmc18's scoped velocity rule ({@link #VELOCITY}); set on a {@code MechanicsProfile.velocity(...)} scope, read everywhere via {@code ctx.victimVelocity()} (so it is configured once, not pinned onto {@link #kb()}). */
    public static VelocityRule velocity() {
        return VELOCITY;
    }

    /**
     * Quantized victim sprint velocity: the flat sprint-jump {@link #SPRINT_JUMP_IMPULSE} along the victim's facing
     * while its client was sprinting within {@link #SPRINT_BUFFER} ticks, else zero. The single place to fold in any
     * future per-victim sprint-speed modifier (speed effects, attribute scaling, ...).
     */
    private static Vec sprintVel(VelocityContext ctx) {
        return ctx.wasClientRecentlySprinting(TickScaler.duration(SPRINT_BUFFER, KnockbackSystem.KEY))
                ? Directions.fromYaw(ctx.entity().getPosition().yaw()).mul(SPRINT_JUMP_IMPULSE)
                : Vec.ZERO;
    }

    /** Cap-hold release threshold (b/t): the fold's vertical must fall below this before the cap releases. */
    private static final double VERTICAL_HOLD_RELEASE = -VelocityConfig.JUMP_VELOCITY; // -0.42: holds through air-tick 5, releases into decay at tick 6 (walk-off feeds v6)

    /**
     * mmc18 vertical launch cap-hold: while the victim's launch arc is still rising / barely falling (the
     * {@link #VELOCITY reconstructed} vertical velocity above {@link #VERTICAL_HOLD_RELEASE}), vertical knockback
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
     * mmc18 axial sprint drag: a {@code +-(AXIAL_iFH * sprintSpeed)} push locked to one cardinal axis, summed onto
     * the final knockback - a drag opposing the victim's sprint momentum. It's snapped to an axis (unlike the linear
     * {@code frictionH} term), which is why it's its own {@link KnockbackComponent}. Today {@code 0.475 * 0.2 = 0.095},
     * but any sprint-speed change in {@link #VELOCITY} scales it.
     *
     * <p><b>Gating.</b> Self-gated to a melee hit by a recently {@link SprintTracker#wasRecentlySprinting sprinting}
     * attacker; the victim-sprinting gate is folded into {@link #VELOCITY} (zero speed -> no contribution). The
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
        if (!SprintTracker.wasRecentlySprinting(tracker, attacker, TickScaler.duration(SPRINT_BUFFER, KnockbackSystem.KEY))) return null;     // sprint hit

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
     * mmc18 range reduction - a linear <em>limit</em> on knockback by distance, not a scale-down: the
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
