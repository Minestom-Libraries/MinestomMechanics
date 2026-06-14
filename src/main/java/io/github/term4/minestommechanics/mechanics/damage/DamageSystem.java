package io.github.term4.minestommechanics.mechanics.damage;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Services;
import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.api.event.DamageEvent;
import io.github.term4.minestommechanics.mechanics.damage.DamageCalculator.DamageResult;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.DamageContext;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfigResolver.ResolvedDamageConfig;
import io.github.term4.minestommechanics.mechanics.damage.silent.HurtSuppression;
import io.github.term4.minestommechanics.mechanics.damage.silent.SilentDamage;
import io.github.term4.minestommechanics.tracking.EnvironmentalDamageTicker;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageType;
import io.github.term4.minestommechanics.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.minestommechanics.mechanics.damage.types.playerattack.PlayerAttack;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSnapshot;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.util.TickClock;
import io.github.term4.minestommechanics.util.TickState;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.item.ItemStack;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Main damage system. Resolves config, computes the final amount, fires the {@link DamageEvent}
 * API, applies the 1.8 overdamage replacement rule, and applies damage. Mirrors KnockbackSystem.
 *
 * <p>Hurt velocity broadcast: every fresh hit except drowning broadcasts the victim's server-tracked
 * velocity (1.8 {@code ac()} -&gt; {@code EntityTrackerEntry}; 26.1 {@code markHurt()} -&gt;
 * {@code ClientboundSetEntityMotionPacket}). Non-melee hits route it through the {@link KnockbackSystem}
 * with {@link DamageConfig#hurtKnockback}; melee's broadcast is its own knockback (vanilla coalesces both
 * into one end-of-tick packet).
 */
public final class DamageSystem {

    private static final Tag<TickState> INVUL_DAMAGE = Tag.Transient("mm:invul-damage");
    /** Amount of the hit that opened the current invulnerability window (for overdamage replacement). */
    private static final Tag<Float> LAST_DAMAGE = Tag.Transient("mm:last-damage");
    /** Melee weapon that opened the current invulnerability window ({@code null} = fist / non-melee). */
    private static final Tag<ItemStack> OPENING_ITEM = Tag.Transient("mm:opening-hit-item");

    /** Default invul ticks when no config / per-type value resolves.
     *  TODO: Scale by TPS when a TPS scaling system is added. */
    public static final int DEFAULT_INVUL_TICKS = 10;

    private final MinestomMechanics mm;
    private final DamageConfig config;
    private final DamageCalculator calc;
    private final DamageTypeRegistry registry;
    private final Services services;
    private final EventNode<@NotNull Event> node;

    public DamageSystem(MinestomMechanics mm, DamageConfig config) {
        this.mm = mm;
        this.node = EventNode.all("mm:damage");
        this.config = config;
        this.services = mm.services();
        this.calc = new DamageCalculator(this.services, Vanilla18.dmg());
        this.registry = new DamageTypeRegistry(this, mm).registerVanillaDefaults();
    }

    /** Effective config for a snapshot carrying none: the victim's scoped profile, else the install config. */
    private DamageConfig configFor(@Nullable Entity target) {
        DamageConfig scoped = mm.profiles().damageFor(target);
        return scoped != null ? scoped : config;
    }

    /**
     * Builds the resolution context for a snapshot, applying the standard config chain when the
     * snapshot carries no config (victim's scoped profile -> install config). Producers use this to
     * read per-type knobs ({@code enabled}, base amount, ignite ticks, ...) before emitting.
     */
    public DamageContext contextFor(DamageSnapshot snap) {
        DamageSnapshot working = snap.config() != null ? snap : snap.withConfig(configFor(snap.target()));
        return DamageContext.of(working, services);
    }

    /** Whether {@code type} is enabled for {@code target} under its effective config chain. */
    public boolean typeEnabled(DamageType type, Entity target) {
        DamageContext ctx = contextFor(DamageSnapshot.of(target, type));
        return ctx.typeConfig().enabled(ctx);
    }

    /**
     * Outcome of a {@link #apply} call, mirroring vanilla 1.8 {@code EntityLiving.damageEntity}: rulesets gate
     * hit side effects on it (knockback on {@link #FULL_HIT}, sprint reset on {@link #landed()}).
     */
    public enum HitResult {
        /** Absorbed (invul window / cancelled / zero amount) - vanilla {@code damageEntity} returned false. */
        BLOCKED,
        /**
         * Overdamage replacement inside the invul window - damage dealt and vanilla returns true, but the
         * fresh-hit effects (base knockback, hurt animation) are skipped ({@code flag = false}).
         */
        OVERDAMAGE,
        /** Fresh hit - full effects. */
        FULL_HIT;

        /** Vanilla {@code damageEntity}'s boolean: the hit dealt damage (fresh or replacement). */
        public boolean landed() { return this != BLOCKED; }
    }

    /**
     * Applies damage from a snapshot. The base amount comes from the snapshot/type via the
     * {@link DamageCalculator}; type-specific modifiers (e.g. the melee crit multiplier) are baked
     * into the snapshot by the producing {@link DamageType} before it is applied. Returns the
     * {@link HitResult} so rulesets can gate hit side effects vanilla-style.
     */
    public HitResult apply(DamageSnapshot snap) {
        if (!(snap.target() instanceof LivingEntity)) return HitResult.BLOCKED;

        // Config chain: snapshot override -> victim's scoped profile -> install config.
        DamageSnapshot working = snap.config() != null ? snap : snap.withConfig(configFor(snap.target()));
        DamageResult result = calc.compute(working);

        float amount = result.amount();

        // TODO(events): restructure the event layer into phased events (PreDamageEvent -> DamageModifyEvent ->
        //  FinalDamageEvent) so listeners hook a specific phase instead of one DamageEvent doing everything;
        //  carry the resolved config on the event to kill the double-resolution in KnockbackSystem.apply too.
        DamageEvent event = new DamageEvent(working, amount);
        EventDispatcher.call(event);
        if (event.isCancelled()) return HitResult.BLOCKED;

        DamageSnapshot finalSnap = event.finalSnap();
        if (!(finalSnap.target() instanceof LivingEntity living)) return HitResult.BLOCKED;

        DamageType type = finalSnap.type();
        DamageContext typeCtx = contextFor(finalSnap);
        // Per-type config from the active DamageConfig (override), else the type's defaults.
        DamageTypeConfig typeCfg = typeCtx.typeConfig();
        // Per-scope kill switch (mirrors AttackConfig.enabled): read off the final snapshot so
        // listeners may still swap in an enabled config to let a specific hit through.
        if (!typeCfg.enabled(typeCtx)) return HitResult.BLOCKED;
        amount = event.amount();
        boolean bypass = event.bypassInvul() || typeCfg.bypassInvul(typeCtx);

        // Vanilla abilities.isInvulnerable: creative/spectator players take NO damage from a non-bypassing source.
        // Returning BLOCKED here makes melee/projectile knockback and arrow deflect (which all key off the
        // landed()/FULL_HIT result) treat them as invulnerable too - not just the raw health. (Void / admin-kill
        // types set bypassInvul to still apply.)
        if (!bypass && living instanceof Player gmPlayer
                && (gmPlayer.getGameMode() == GameMode.CREATIVE || gmPlayer.getGameMode() == GameMode.SPECTATOR)) {
            return HitResult.BLOCKED;
        }

        ResolvedDamageConfig resolved = calc.resolveConfig(
                finalSnap.config() != null ? finalSnap : finalSnap.withConfig(configFor(finalSnap.target())));

        // Each knob: per-type override when set, else the global config value.
        boolean overdamage = Boolean.TRUE.equals(pick(typeCfg.overdamage(typeCtx), resolved.enableOverdamage()));
        // Silent (no hurt animation): default false when unset anywhere.
        boolean generalSilent = Boolean.TRUE.equals(pick(typeCfg.silent(typeCtx), resolved.silent()));

        if (event.invulnerable() && !bypass) {
            // overdamage replacement
            if (!overdamage) return HitResult.BLOCKED;
            float applied = amount > event.stored() ? amount - event.stored() : 0f;
            applied = applyComponents(typeCtx, event, applied, true);
            if (applied > 0) {
                // Overdamage-specific silent override; falls back to the general silent flag when unset.
                Boolean odSilent = pick(typeCfg.overdamageSilent(typeCtx), resolved.overdamageSilent());
                boolean replacementSilent = odSilent != null ? odSilent : generalSilent;
                living.setTag(LAST_DAMAGE, Math.max(event.stored(), amount));
                applyDamage(living, type, finalSnap, applied, replacementSilent);
                return HitResult.OVERDAMAGE;
            }
            return HitResult.BLOCKED;
        }

        amount = applyComponents(typeCtx, event, amount, false);
        // A zero-damage hit still LANDS when its type triggers invul - vanilla's 0-damage thrown projectiles
        // (snowball/egg) play the hurt animation and open the invul window (the gate that rejects further
        // projectile hits). Only a negative amount, or a 0 that does not trigger invul, is dropped.
        boolean triggersInvul = typeCfg.triggersInvul(typeCtx);
        if (amount < 0 || (amount == 0f && !triggersInvul)) return HitResult.BLOCKED;

        storeOpeningItem(living, finalSnap.item());
        living.setTag(LAST_DAMAGE, amount);
        applyDamage(living, type, finalSnap, amount, generalSilent);
        // Vanilla ac(): every fresh hit except drowning broadcasts the victim's server-tracked velocity.
        // Melee and projectiles own their broadcast through the KnockbackSystem (vanilla coalesces ac() + the
        // directional knockback into one end-of-tick packet); a second send here would overwrite it.
        if (Boolean.TRUE.equals(resolved.syncHurtVelocity())
                && !knockbackOwnsVelocity(type)
                && !DROWN_KEY.equals(type.key())) {
            applyHurtKnockback(living, resolved.hurtKnockback());
        }
        Integer invulTicks = pick(typeCfg.invulTicks(typeCtx), resolved.invulTicks());
        if (triggersInvul && invulTicks != null && invulTicks > 0) {
            setDamageInvulnerable(living, invulTicks);
        }
        return HitResult.FULL_HIT;
    }

    /**
     * Whether the type's knockback (the attack ruleset / projectile, via the {@code KnockbackSystem}) owns the
     * velocity broadcast - so {@code DamageSystem} must not also send the generic hurt velocity (double-set).
     * TODO(stages): make this a {@code DamageTypeConfig} flag instead of a key check as more types route KB.
     */
    private static boolean knockbackOwnsVelocity(DamageType type) {
        return PlayerAttack.KEY.equals(type.key())
                || io.github.term4.minestommechanics.mechanics.damage.types.projectile.ProjectileDamage.KEY.equals(type.key());
    }

    /** Per-type override when non-null, else the global config value (which may itself be null). */
    private static <T> @Nullable T pick(@Nullable T typeValue, @Nullable T globalValue) {
        return typeValue != null ? typeValue : globalValue;
    }

    /** Vanilla {@code damageEntity}: drowning is the one source that never triggers {@code ac()}. */
    private static final Key DROWN_KEY = Key.key("minecraft:drown");
    /** Fallback hurt knockback when no config sets one (built once - it is immutable). */
    private static final KnockbackConfig DEFAULT_HURT_KB = Vanilla18.hurtKb();

    /**
     * The hurt velocity broadcast, vanilla parity: a fresh hit sets {@code velocityChanged} ({@code ac()},
     * gated by a knockback-resistance roll) and {@code EntityTrackerEntry} broadcasts the server's
     * {@code motX/motY/motZ} (26.1: {@code markHurt} -&gt; {@code ClientboundSetEntityMotionPacket}). Routed
     * through the {@link KnockbackSystem} with {@link DamageConfig#hurtKnockback} - a zero-impulse config whose
     * velocity fold IS the broadcast - so all velocity sends share one path and one set of knobs.
     */
    private void applyHurtKnockback(LivingEntity living, @Nullable KnockbackConfig cfg) {
        KnockbackSystem kb = services.knockback();
        if (kb == null) return;
        if (ThreadLocalRandom.current().nextDouble() < living.getAttributeValue(Attribute.KNOCKBACK_RESISTANCE)) return;
        kb.apply(new KnockbackSnapshot(living, false, null,
                living.getPosition(), living.getPosition().direction(),
                cfg != null ? cfg : DEFAULT_HURT_KB));
    }

    private float applyComponents(DamageContext ctx, DamageEvent event, float amount, boolean overdamage) {
        DamageConfig cfg = event.config();
        if (cfg == null) cfg = configFor(event.target());
        if (cfg.subConfig != null) {
            DamageConfig sub = cfg.subConfig.apply(ctx);
            if (sub != null) cfg = sub.fromBase(cfg);
        }
        List<DamageComponent> components = cfg.customComponents;
        if (components == null || components.isEmpty()) return amount;
        for (DamageComponent component : components) {
            Float next = component.apply(ctx, event, amount, overdamage);
            if (next != null) amount = next;
        }
        return amount;
    }

    private static void storeOpeningItem(LivingEntity living, @Nullable ItemStack item) {
        if (item != null && !item.isAir()) living.setTag(OPENING_ITEM, item);
        else living.removeTag(OPENING_ITEM);
    }

    /** Melee weapon that opened the target's current damage-invul window, or {@code null} (fist / none). */
    public static @Nullable ItemStack openingHitItem(LivingEntity target) {
        return target.getTag(OPENING_ITEM);
    }

    private void applyDamage(LivingEntity living, DamageType type, DamageSnapshot snap, float amount, boolean silent) {
        // Non-lethal silent hits update health via the no-hurt path; lethal hits fall through to
        // living.damage() so Minestom handles death (message, drops, respawn).
        float newHealth = (float) Math.max(0, living.getHealth() - amount);
        if (silent && living instanceof Player p && newHealth > 0) {
            SilentDamage.setHealthWithoutHurtEffect(p, newHealth, mm.clientInfo());
            return;
        }
        Entity source = snap.source();
        Damage damage = new Damage(type.minecraftType(), source, source, snap.point(), amount);
        living.damage(damage);
    }

    public DamageConfig config() { return config; }

    /**
     * Effective invulnerability ticks for a given type: the per-type override when set, else the
     * configured global value, else {@link #DEFAULT_INVUL_TICKS} (when unset or
     * context-dependent). A {@code null} type resolves the global value only.
     */
    public int defaultInvulTicks(@Nullable DamageType type) {
        // Hit-independent default: only constant invul values are meaningful here. A context-dependent
        // (per-hit) value can't be evaluated without a snapshot, so it falls through to the global/default.
        if (type != null) {
            DamageTypeConfig tcfg = config.typeConfig(type.key());
            if (tcfg == null) tcfg = type.defaultConfig();
            Integer v = tcfg.invulTicksConstant();
            if (v != null) return v;
        }
        Integer v = config.invulTicks != null ? config.invulTicks.constantOrNull() : null;
        return v != null ? v : DEFAULT_INVUL_TICKS;
    }

    /**
     * Standard hit i-frame window used by other systems (attack/knockback) to align their invul
     * windows. Reflects the {@code player_attack} type's effective invul, so a custom melee
     * {@link DamageTypeConfig#invulTicks(DamageContext)} propagates to those windows without touching their resolvers.
     */
    public int defaultInvulTicks() {
        return defaultInvulTicks(registry.get(PlayerAttack.KEY));
    }

    /** Registry of damage types and their handlers. */
    public DamageTypeRegistry registry() { return registry; }

    /** This system's listener node ({@code mm:damage}); everything the system hooks lives under it. */
    public EventNode<@NotNull Event> node() { return node; }

    /**
     * Installs the damage system. The config decides what runs: every type with an entry in
     * {@link DamageConfig#typeConfigs} is enabled (self-driven producers - fall, the burning family, cactus -
     * start; one-off types like melee enable as data-only no-ops). The per-type {@code enabled} knob remains
     * the per-hit/scope gate on top. {@code extraTypes} registers and enables custom types built outside the
     * config. Runtime toggling stays available via {@link #registry()}.
     */
    public static DamageSystem install(MinestomMechanics mm, DamageConfig cfg, DamageType... extraTypes) {
        var system = new DamageSystem(mm, cfg);
        mm.registerDamage(system);
        EnvironmentalDamageTicker.instance().bind(system, mm);
        HurtSuppression.install(system.node);
        mm.install(system.node);
        for (DamageType type : extraTypes) {
            if (!system.registry.contains(type.key())) system.registry.register(type);
        }
        for (Key key : cfg.typeConfigs.keySet()) {
            if (system.registry.contains(key)) system.registry.enable(key);
        }
        for (DamageType type : extraTypes) system.registry.enable(type.key());
        return system;
    }

    /**
     * Producer-side early-out mirroring vanilla {@code damageEntity}'s pre-event check: while the
     * target's window is active, an attempt that cannot beat the stored highwater is dropped before
     * any {@link DamageEvent} fires. Repeating producers (fire, cactus) use this to avoid spamming
     * blocked events every tick; one-shot sources (melee, fall) just call {@link #apply}.
     */
    public static boolean absorbedByWindow(LivingEntity target, float amount) {
        return isInvulnerableToDamage(target) && amount <= lastDamage(target);
    }

    /** The "last damage" highwater stored for the target's current invul window ({@code 0} if none). */
    public static float lastDamage(LivingEntity le) {
        Float v = le.getTag(LAST_DAMAGE);
        return v != null ? v : 0f;
    }

    public static void setDamageInvulnerable(Entity e, int duration) {
        if (!(e instanceof LivingEntity le) || duration <= 0) return;
        le.setTag(INVUL_DAMAGE, new TickState(TickClock.now(), duration));
    }

    public static boolean isInvulnerableToDamage(Entity e) {
        if (!(e instanceof LivingEntity le)) return false;
        TickState s = getDamageInvul(le);
        return s != null && s.isActive();
    }

    public static int remainingDamageInvulTicks(LivingEntity le) {
        TickState s = getDamageInvul(le);
        return s != null ? s.remainingTicks() : 0;
    }

    private static @Nullable TickState getDamageInvul(LivingEntity le) {
        return le.getTag(INVUL_DAMAGE);
    }
}
