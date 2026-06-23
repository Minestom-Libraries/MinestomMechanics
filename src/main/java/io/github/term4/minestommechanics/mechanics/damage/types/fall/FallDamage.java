package io.github.term4.minestommechanics.mechanics.damage.types.fall;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.damage.DamageProducers;
import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.attribute.defense.ProtectionCategory;
import io.github.term4.minestommechanics.tracking.motion.MotionTracker;
import io.github.term4.minestommechanics.util.BlockContact;
import io.github.term4.minestommechanics.util.tick.TickPhase;
import io.github.term4.minestommechanics.util.tick.TickSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.VanillaTypes;
import net.kyori.adventure.key.Key;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fall damage ({@code minecraft:fall}). Vanilla 1.8: distance accumulates while descending, water/climbing zero it,
 * lava halves it, and landing applies damage from {@link FallDamageConfig}.
 *
 * <p>Self-driven: players are tracked off their own move packets (with a per-tick poll for status-only onGround
 * packets); other living entities per tick. Creative/spectator/flying are exempt; (re)spawn resets. A plain teleport does
 * NOT reset (vanilla 1.8/26 leave fall distance on teleport - the pearl zeroes it explicitly); callers reset via {@link #resetFallDistance}.
 */
public final class FallDamage extends DamageType {

    public static final Key KEY = Key.key("minecraft:fall");
    public static final FallDamage INSTANCE = new FallDamage();

    /** Accumulated fall distance in blocks (absent = 0). */
    private static final Tag<Float> FALL_DISTANCE = Tag.Transient("mm:fall-distance");
    /** Previous observation (move packet for players, tick for others): y + onGround for delta/landing detection. */
    private static final Tag<PrevMove> PREV = Tag.Transient("mm:fall-prev");

    private record PrevMove(double y, boolean onGround) {}

    private @Nullable EventNode<@NotNull Event> node;
    private @Nullable DamageSystem system;
    /** The landing poll is registered on {@link TickSystem} once for the JVM (afterAdvance has no removal); {@link #polling} gates it. */
    private static final AtomicBoolean POLL_HOOK = new AtomicBoolean();
    private volatile boolean polling;

    private FallDamage() {
        super(KEY, "Fall", VanillaTypes.FALL, FallDamageConfig.builder().build());
    }

    /** Fall is the Feather Falling category (plus general Protection); it bypasses armor but not EPF. */
    @Override public Set<ProtectionCategory> protectionCategories() { return Set.of(ProtectionCategory.FALL); }

    @Override
    public void enable(DamageSystem system, MinestomMechanics mm) {
        this.system = system;
        EventNode<@NotNull Event> n = EventNode.all("mm:fall-damage");
        n.addListener(PlayerMoveEvent.class, this::onMove);
        n.addListener(EntityTickEvent.class, this::onTick);
        n.addListener(PlayerSpawnEvent.class, e -> resetFallDistance(e.getPlayer()));
        system.node().addChild(n);
        node = n;
        // fallback poll a tick behind onMove (catches status-only onGround landings, no PlayerMoveEvent), via the TickSystem
        // like the other per-tick mechanics. Registered once for the JVM; `polling` makes disable() inert.
        polling = true;
        if (POLL_HOOK.compareAndSet(false, true)) TickSystem.register(TickPhase.DEFAULT, ctx -> INSTANCE.pollLandings(ctx.instance()));
    }

    @Override
    public void disable() {
        if (system != null && node != null) system.node().removeChild(node);
        node = null;
        polling = false;
    }

    /** Clears an entity's accumulated fall distance ((re)spawn + explicit resets like the ender pearl; vanilla does NOT reset on a plain teleport). */
    public static void resetFallDistance(Entity entity) {
        entity.removeTag(FALL_DISTANCE);
        entity.removeTag(PREV);
    }

    /** The entity's currently accumulated fall distance in blocks. */
    public static float fallDistance(Entity entity) {
        Float v = entity.getTag(FALL_DISTANCE);
        return v != null ? v : 0f;
    }

    /** Players: client-authoritative deltas off their own move packets (ping-invariant landings). */
    private void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Pos newPos = e.getNewPosition();
        PrevMove prev = p.getTag(PREV);
        p.setTag(PREV, new PrevMove(newPos.y(), e.isOnGround()));

        if (DamageProducers.exempt(p)) {
            p.removeTag(FALL_DISTANCE);
            return;
        }
        if (prev == null) return; // need a baseline first
        // ground = client flag or the server collision (MotionTracker), the same source combat ground checks use
        accumulate(p, newPos.y() - prev.y(), e.isOnGround() || MotionTracker.simCollided(p));
    }

    /** Non-player living entities: server-side per-tick deltas. */
    private void onTick(EntityTickEvent e) {
        if (e.getEntity() instanceof Player) return; // players ride their own move packets
        if (!(e.getEntity() instanceof LivingEntity living) || living.isDead()) return;
        if (living.getInstance() == null) return;
        double y = living.getPosition().y();
        boolean onGround = living.isOnGround();
        PrevMove prev = living.getTag(PREV);
        living.setTag(PREV, new PrevMove(y, onGround));
        if (prev == null) return;
        accumulate(living, y - prev.y(), onGround);
    }

    /** Fallback landing poll for players (status-only onGround packets fire no move event); one instance per tick. */
    private void pollLandings(Instance instance) {
        if (!polling) return;
        for (Player p : instance.getPlayers()) {
            if (!p.isOnGround()) continue;
            float dist = fallDistance(p);
            if (dist <= 0) continue;
            if (!DamageProducers.exempt(p)) land(p, dist);
            p.removeTag(FALL_DISTANCE);
        }
    }

    /** One observation step: apply the environment rules (water/climbing zero, lava halves), then land or accumulate. */
    private void accumulate(LivingEntity living, double dy, boolean onGround) {
        float dist = fallDistance(living);

        // only consulted mid-fall
        if (dist > 0 || dy < 0) {
            boolean[] contact = new boolean[2]; // water, lava
            BlockContact.scan(living, block -> {
                if (block.compare(Block.WATER)) contact[0] = true;
                else if (block.compare(Block.LAVA)) contact[1] = true;
                return contact[0] && contact[1];
            });
            if (contact[0] || climbing(living)) {
                living.removeTag(FALL_DISTANCE);
                dist = 0f;
            } else if (contact[1] && dist > 0) {
                dist *= 0.5f;
                living.setTag(FALL_DISTANCE, dist);
            }
        }

        if (onGround) {
            if (dist > 0) land(living, dist);
            living.removeTag(FALL_DISTANCE);
        } else if (dy < 0) {
            living.setTag(FALL_DISTANCE, dist + (float) -dy);
        }
    }

    /** Vanilla 1.8 climbable set: ladder or vine at the feet block. */
    private static boolean climbing(LivingEntity living) {
        if (living.getInstance() == null) return false;
        Block feet = living.getInstance().getBlock(living.getPosition(), Block.Getter.Condition.TYPE);
        return feet != null && (feet.compare(Block.LADDER) || feet.compare(Block.VINE));
    }


    /** Emits the landing's damage snapshot with the fall distance as the {@code detail} payload. */
    private void land(LivingEntity living, float distance) {
        DamageSystem sys = this.system;
        if (sys == null) return;
        DamageSnapshot snap = DamageSnapshot.of(living, this).withDetail(FallDetail.of(distance));
        DamageContext ctx = sys.contextFor(snap);
        if (!ctx.typeConfig().enabled(ctx)) return;
        // skip below-threshold landings before any event fires
        if (ctx.baseAmount() <= 0) return;
        sys.apply(snap);
    }
}
