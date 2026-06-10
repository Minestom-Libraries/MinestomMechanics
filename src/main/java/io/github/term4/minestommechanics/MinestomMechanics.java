package io.github.term4.minestommechanics;

import io.github.term4.echofix.EchoFix;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.platform.client.ClientInfoService;
import io.github.term4.minestommechanics.platform.client.VersionDetector;
import io.github.term4.minestommechanics.tracking.MotionTracker;
import io.github.term4.minestommechanics.tracking.SprintTracker;
import io.github.term4.minestommechanics.util.TickClock;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MinestomMechanics {

    // This is the main initialization class for the library.
    //  This class allows the user to enable / disable server level systems (version detection, maybe packet interception, nothing else for now)

    // Server level options (defaults)
    /** When enabled the server listens for player details sent from ViaVersion proxy message */
    public boolean viaProxyDetails = true; // false by default

    /** When enabled MinestomMechanics manually tracks a players sprinting status (useful for combat). Default: true */
    public boolean installSprintTracker = true;
    /** When enabled, tracks per-entity air-time, launch state, and position-delta motion (drives knockback velocity). Default: true */
    public boolean installMotionTracker = true;

    public boolean metaFix = true;

    // might add an option for packet validation when not using a proxy? probably better to use a separate library for that though
    private final EventNode<@NotNull Event> root = EventNode.all("mm:root");
    private final EventNode<@NotNull Event> apiEvents = EventNode.all("mm:api-events");

    // Server level services
    private ClientInfoService clientInfo;
    private final MechanicsProfiles profiles = new MechanicsProfiles();

    // Optional registry
    private @Nullable SprintTracker sprintTracker;
    private @Nullable MotionTracker motionTracker;
    private @Nullable AttackSystem attackSystem;
    private @Nullable KnockbackSystem knockbackSystem;
    private @Nullable DamageSystem damageSystem;

    void registerSprintTracker(SprintTracker s) { sprintTracker = s; }
    void registerMotionTracker(MotionTracker g) { motionTracker = g; }
    public void registerAttack(AttackSystem a) { attackSystem = a; }
    public void registerKnockback(KnockbackSystem k) { knockbackSystem = k; }
    public void registerDamage(DamageSystem d) { damageSystem = d; }

    public @Nullable SprintTracker sprintTracker() { return sprintTracker; }
    public @Nullable MotionTracker motionTracker() { return motionTracker; }
    public @Nullable AttackSystem attackSystem() { return attackSystem; }
    public @Nullable KnockbackSystem knockbackSystem() { return knockbackSystem; }
    public @Nullable DamageSystem damageSystem() { return damageSystem; }

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

        if (metaFix) {
            EchoFix.install();
            MinecraftServer.getConnectionManager().setPlayerProvider((conn, profile) ->
                    new OptimizedPlayer(conn, profile));
        }

        if (installSprintTracker) {
            var tracker = new SprintTracker();
            registerSprintTracker(tracker);
            root.addChild(tracker.node());
        }
        if (installMotionTracker) {
            var tracker = new MotionTracker();
            tracker.start();
            root.addChild(tracker.node());
            registerMotionTracker(tracker);
        }

        clientInfo = new ClientInfoService();

        // Root node for all of MinestomMechanics
        MinecraftServer.getGlobalEventHandler().addChild(root);

        // Create child nodes
        EventNode<@NotNull Event> detectors = EventNode.all("mm:detectors");

        // Add child nodes to root
        root.addChild(detectors);
        root.addChild(apiEvents);

        root.addListener(PlayerDisconnectEvent.class, e -> clientInfo.remove(e.getPlayer()));

        if (viaProxyDetails) detectors.addChild(VersionDetector.node(clientInfo));
    }

    /** Access registered MinestomMechanics services. */
    public Services services() {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        return new Services(this);
    }

    /** Access client info (e.g. protocol version) from server level detectors */
    public ClientInfoService clientInfo() {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        return clientInfo;
    }

    /**
     * Scoped config profiles (player / instance / global). Assignable any time - including before
     * {@link #init()} - and swappable at runtime; systems consult them per hit.
     */
    public MechanicsProfiles profiles() { return profiles; }

    /** Public node for MinestomMechanics API events */
    public EventNode<@NotNull Event> events() {
        if (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        return apiEvents;
    }

    /** Public method to install a node to the root MinestomMechanics node */
    public void install(EventNode<@NotNull Event> node) {
        if  (!initialized) throw new IllegalStateException("MinestomMechanics has not been initialized");
        root.addChild(node);
    }

    public boolean isInitialized() {
        return initialized;
    }
}
