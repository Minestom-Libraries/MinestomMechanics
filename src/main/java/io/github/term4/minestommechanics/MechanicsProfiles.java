package io.github.term4.minestommechanics;

import io.github.term4.minestommechanics.mechanics.attack.AttackConfig;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeConfig;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingConfig;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableConfig;
import io.github.term4.minestommechanics.mechanics.damage.DamageConfig;
import io.github.term4.minestommechanics.mechanics.durability.DurabilityConfig;
import io.github.term4.minestommechanics.mechanics.hunger.HungerConfig;
import io.github.term4.minestommechanics.platform.fixes.FixesConfig;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.platform.compatibility.CompatConfig;
import io.github.term4.minestommechanics.platform.player.PlayerConfig;
import io.github.term4.minestommechanics.tracking.motion.VelocityRule;
import io.github.term4.minestommechanics.util.tick.TickScalingConfig;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Scoped {@link MechanicsProfile} registry: assign profiles per player, per instance (world), or globally,
 * and swap them at runtime (configs are immutable, so a swap takes effect on the next hit; the {@code player}
 * platform member is pushed by {@code PlayerConfigApplier} on change). Resolution is per <em>member</em>,
 * highest scope first:
 * <pre>player profile -> instance profile -> global profile -> the system's install config</pre>
 * so a partial profile (e.g. knockback only) overrides just that system. With nothing assigned, every hit
 * uses the install configs.
 *
 * <p><b>Scope subject.</b> Attack resolves against the <em>attacker</em> (hit detection/ruleset is the
 * attacker's action); damage and knockback resolve against the <em>victim</em> (whose mechanics they are).
 * In the usual minigame case both share the instance, so the distinction only matters for player overrides.
 *
 * <p>Player and instance assignments live in transient tags, so they clean up with their holder
 * (a player's profile drops on disconnect).
 */
public final class MechanicsProfiles {

    private static final Tag<MechanicsProfile> PROFILE = Tag.Transient("mm:profile");

    private volatile @Nullable MechanicsProfile global;
    /** Notified after any assignment changes; init registers the player platform config push here. */
    private volatile @Nullable Runnable changeHook;

    MechanicsProfiles() {}

    void onChange(Runnable hook) { this.changeHook = hook; }

    private void changed() {
        Runnable hook = changeHook;
        if (hook != null) hook.run();
    }

    /** Sets (or with {@code null} clears) the server-wide fallback profile. */
    public void setGlobal(@Nullable MechanicsProfile profile) {
        this.global = profile;
        changed();
    }
    public @Nullable MechanicsProfile global() { return global; }

    /** Sets (or with {@code null} clears) the profile for an instance (world/dimension). */
    public void setInstance(Instance instance, @Nullable MechanicsProfile profile) {
        if (profile == null) instance.removeTag(PROFILE);
        else instance.setTag(PROFILE, profile);
        changed();
    }
    public @Nullable MechanicsProfile instance(Instance instance) { return instance.getTag(PROFILE); }

    /** Sets (or with {@code null} clears) the profile for a single player (highest precedence). */
    public void setPlayer(Player player, @Nullable MechanicsProfile profile) {
        if (profile == null) player.removeTag(PROFILE);
        else player.setTag(PROFILE, profile);
        changed();
    }
    public @Nullable MechanicsProfile player(Player player) { return player.getTag(PROFILE); }

    /** Effective attack config for {@code subject} (the attacker), or {@code null} when no scope sets one. */
    public @Nullable AttackConfig attackFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::attack);
    }

    /** Effective damage config for {@code subject} (the victim), or {@code null} when no scope sets one. */
    public @Nullable DamageConfig damageFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::damage);
    }

    /** Effective knockback config for {@code subject} (the victim), or {@code null} when no scope sets one. */
    public @Nullable KnockbackConfig knockbackFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::knockback);
    }

    /** Effective player platform config for {@code subject}, or {@code null} when no scope sets one. */
    public @Nullable PlayerConfig playerFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::player);
    }

    /** Effective velocity tracking rule for {@code subject} (the victim), or {@code null} when no scope sets one. */
    public @Nullable VelocityRule velocityFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::velocity);
    }

    /** Effective projectile config for {@code subject} (the shooter), or {@code null} when no scope sets one. */
    public @Nullable ProjectileConfig projectilesFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::projectiles);
    }

    /** Effective client/protocol fixes config for {@code subject}, or {@code null} when no scope sets one. */
    public @Nullable FixesConfig fixesFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::fixes);
    }

    /** Effective attribute config for {@code subject}, or {@code null} when no scope sets one (the install config applies). */
    public @Nullable AttributeConfig attributesFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::attributes);
    }

    /** Effective TPS-scaling config for {@code subject}, or {@code null} when no scope sets one ({@link TickScalingConfig#DEFAULTS} apply). */
    public @Nullable TickScalingConfig scalingFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::tickScaling);
    }

    /** Effective durability config for {@code subject} (the item holder), or {@code null} when no scope sets one. */
    public @Nullable DurabilityConfig durabilityFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::durability);
    }

    /** Effective hunger config for {@code subject}, or {@code null} when no scope sets one. */
    public @Nullable HungerConfig hungerFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::hunger);
    }

    /** Effective consumable config for {@code subject} (the consumer), or {@code null} when no scope sets one. */
    public @Nullable ConsumableConfig consumablesFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::consumables);
    }

    /** Effective blocking config for {@code subject} (the defender), or {@code null} when no scope sets one. */
    public @Nullable BlockingConfig blockingFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::blocking);
    }

    /** Effective cross-version compatibility config for {@code subject}, or {@code null} when no scope sets one. */
    public @Nullable CompatConfig compatFor(@Nullable Entity subject) {
        return resolve(subject, MechanicsProfile::compat);
    }

    private <T> @Nullable T resolve(@Nullable Entity subject, Function<MechanicsProfile, @Nullable T> member) {
        if (subject != null) {
            if (subject instanceof Player p) {
                T v = memberOf(p.getTag(PROFILE), member);
                if (v != null) return v;
            }
            Instance in = subject.getInstance();
            if (in != null) {
                T v = memberOf(in.getTag(PROFILE), member);
                if (v != null) return v;
            }
        }
        return memberOf(global, member);
    }

    private static <T> @Nullable T memberOf(@Nullable MechanicsProfile profile,
                                            Function<MechanicsProfile, @Nullable T> member) {
        return profile != null ? member.apply(profile) : null;
    }
}
