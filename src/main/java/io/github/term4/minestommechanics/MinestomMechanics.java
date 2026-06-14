package io.github.term4.minestommechanics;

import io.github.term4.echofix.EchoFix;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import io.github.term4.minestommechanics.platform.player.PlayerConfigApplier;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.tracking.ClientInfoTracker;
import io.github.term4.minestommechanics.tracking.MotionTracker;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.tracking.Tracker;
import io.github.term4.minestommechanics.util.TickClock;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MinestomMechanics {

    // Main initialization class for the library: server-level options (trackers, metaFix), the node tree
    // (mm:root -> trackers / systems / api-events), the system registry, and scoped config profiles.

    // Server level options (defaults)
    /** When enabled the server listens for player details sent from ViaVersion proxy message */
    public boolean viaProxyDetails = true;

    /** When enabled MinestomMechanics manually tracks a players sprinting status (useful for combat). Default: true */
    public boolean installSprintTracker = true;
    /** When enabled, tracks per-entity air-time, launch state, and position-delta motion (drives knockback velocity). Default: true */
    public boolean installMotionTracker = true;
    /** When enabled, the stutter seen on 1.9+ clients when changing poses (sneaking, sprinting, etc.) due to high ping will not be present. Requires {@link #installPlayerProvider}. */
    public boolean metaFix = true;
    /**
     * When enabled, installs the {@code OptimizedPlayer} provider and the scoped {@code PlayerConfig}
     * application (e.g. {@code positionBroadcastInterval}). Independent of {@link #metaFix} - broadcast
     * throttling works without the meta fix. Disable only if you set your own player provider; extend
     * {@code OptimizedPlayer} there to keep PlayerConfig support. Default: true
     */
    public boolean installPlayerProvider = true;

    // might add an option for packet validation when not using a proxy? probably better to use a separate library for that though
    private final EventNode<@NotNull Event> root = EventNode.all("mm:root");
    private final EventNode<@NotNull Event> apiEvents = EventNode.all("mm:api-events");

    // Server level services
    private ClientInfoTracker clientInfo;
    private final MechanicsProfiles profiles = new MechanicsProfiles();

    // Optional registry
    private @Nullable SprintTracker sprintTracker;
    private @Nullable MotionTracker motionTracker;
    private @Nullable AttackSystem attackSystem;
    private @Nullable KnockbackSystem knockbackSystem;
    private @Nullable DamageSystem damageSystem;
    private @Nullable ProjectileSystem projectileSystem;

    public void registerAttack(AttackSystem a) { attackSystem = a; }
    public void registerKnockback(KnockbackSystem k) { knockbackSystem = k; }
    public void registerDamage(DamageSystem d) { damageSystem = d; }
    public void registerProjectiles(ProjectileSystem p) { projectileSystem = p; }

    public @Nullable SprintTracker sprintTracker() { return sprintTracker; }
    public @Nullable MotionTracker motionTracker() { return motionTracker; }
    public @Nullable AttackSystem attackSystem() { return attackSystem; }
    public @Nullable KnockbackSystem knockbackSystem() { return knockbackSystem; }
    public @Nullable DamageSystem damageSystem() { return damageSystem; }
    public @Nullable ProjectileSystem projectileSystem() { return projectileSystem; }

    /** Vanilla 1.8 keep-alive cadence (40 ticks). */
    public static final long LEGACY_KEEP_ALIVE_MS = 2000;

    /**
     * Sets the keep-alive interval - which is also how often {@link net.minestom.server.entity.Player#getLatency()}
     * refreshes (Minestom measures the round-trip on the read thread, sub-tick accurate; only the cadence is
     * coarse, defaulting to 10s). MUST be called before {@code MinecraftServer.init()}: the flag is read once
     * at {@code ServerFlag} class-load. {@link #LEGACY_KEEP_ALIVE_MS} = vanilla 1.8's 2s cadence; pass smaller
     * (e.g. 500, Hypixel's dedicated probe rate) for fresher latency reads.
     */
    public static void keepAliveInterval(long millis) {
        System.setProperty("minestom.keep-alive-delay", Long.toString(millis));
    }

    private static final MinestomMechanics INSTANCE = new MinestomMechanics();
    private boolean initialized = false;

    private MinestomMechanics() {}

    public static MinestomMechanics getInstance() { return INSTANCE; }

    /** Initialize with current options (or defaults if no options specified) */
    public void init() {
        if (initialized) return;
        initialized = true;

        // Enable always-necessary functions
        TickClock.start();

        if (metaFix && !installPlayerProvider) {
            System.out.println("[mm] metaFix is enabled but installPlayerProvider is not - the meta fix needs the"
                    + " EchoFix player provider and will be inert (set your own provider extending OptimizedPlayer).");
        }
        if (metaFix) EchoFix.install();
        if (installPlayerProvider) {
            MinecraftServer.getConnectionManager().setPlayerProvider((conn, profile) ->
                    new OptimizedPlayer(conn, profile));
            // Scoped PlayerConfig (profiles) -> OptimizedPlayer, applied at spawn (join / instance
            // change) and pushed to online players whenever a profile assignment changes.
            PlayerConfigApplier.install(this);
            profiles.onChange(() -> PlayerConfigApplier.applyAll(this));
        }

        // Root node for all of MinestomMechanics
        MinecraftServer.getGlobalEventHandler().addChild(root);
        root.addChild(apiEvents);

        // Trackers: per-player state stamped off events; each enabled one mounts under a shared node.
        trackersNode = EventNode.all("mm:trackers");
        root.addChild(trackersNode);

        clientInfo = new ClientInfoTracker();
        if (installSprintTracker) mountTracker(sprintTracker = new SprintTracker());
        if (installMotionTracker) mountTracker(motionTracker = new MotionTracker());
        if (viaProxyDetails) mountTracker(clientInfo);
    }

    private EventNode<@NotNull Event> trackersNode;

    /** Mounts a tracker under {@code mm:trackers} (init-time or lazily from installed systems). */
    public void mountTracker(Tracker tracker) {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        mount(trackersNode, tracker);
    }

    /** Starts a tracker and mounts its listener node. */
    private static void mount(EventNode<@NotNull Event> trackers, Tracker tracker) {
        tracker.start();
        trackers.addChild(tracker.node());
    }

    /** Access registered MinestomMechanics services. */
    public Services services() {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        return new Services(this);
    }

    /** Access client info (e.g. protocol version) stamped by the client info tracker */
    public ClientInfoTracker clientInfo() {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        return clientInfo;
    }

    /**
     * Scoped config profiles (player / instance / global). Assignable any time - including before
     * {@link #init()} - and swappable at runtime; systems consult them per hit.
     */
    public MechanicsProfiles profiles() { return profiles; }

    /**
     * Convenience node for MinestomMechanics API event listeners. API events are dispatched through the
     * global handler, so listening here or on {@code MinecraftServer.getGlobalEventHandler()} both work.
     */
    public EventNode<@NotNull Event> events() {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        return apiEvents;
    }

    /** Public method to install a node to the root MinestomMechanics node */
    public void install(EventNode<? extends @NotNull Event> node) {
        if  (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        root.addChild(node);
    }

    public boolean isInitialized() {
        return initialized;
    }
}
