package test;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.Vanilla18;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import test.presets.Hypixel;
import io.github.term4.minestommechanics.platform.client.ClientInfoService;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

public class ExampleServer {
    /** Debug: default simulated inbound packet latency (ticks) applied to the test account below. ~300ms @ 20 TPS. */
    private static final long SIM_LAG_TICKS = 6;
    /** Debug: account that auto-gets {@link #SIM_LAG_TICKS} of {@link LagSimulator} lag so the laggy-landing knockback is reproducible locally. */
    private static final String SIM_LAG_USERNAME = "NotXaylahhLol";

    static void main() {
        // Could wrap these in compatibility methods (mm.legacyProperties(mode: 1.7, 1.8, etc)

        // Enable faster socket writes
        System.setProperty("minestom.new-socket-write-lock", "true");

        // Disable interaction range enforcement (mechanics lib handles reach)
        System.setProperty( "minestom.enforce-entity-interaction-range", "false");

        // Set up required flags for legacy players (prevents visual bugs on older versions)
        System.setProperty("minestom.chunk-view-distance", "12"); // less than 12 causes players to disappear at ~150 block from spawn

        // Set server TPS (default is 20, library should work with any TPS tested up to 1000)
        System.setProperty("minestom.tps", "20");

        // Initialize the server
        MinecraftServer server = MinecraftServer.init(new Auth.Bungee()); // bungee auth allows 1.7 clients to join (velocity works for all later versions, and a proxy is not required)

        // Debug: test-only inbound-lag simulator (delays a player's movement packets so the server perceives
        // their position/onGround late, like a high-ping client). Purely in the test server - mechanics is untouched.
        final LagSimulator lag = new LagSimulator();
        lag.install();

        // Get mm instance
        MinestomMechanics mm = MinestomMechanics.getInstance();

        // Enable ViaVersion proxy details
        mm.viaProxyDetails = true;
        mm.init();

        // Debug (temporary): melee hits flash + knock back + open invul like normal, but deal no net health - so a
        // victim can be float-tested indefinitely without dying.
        DamageSystem.DEBUG_ZERO_MELEE_DAMAGE = true;

        // 1. Initialize knockback system
        KnockbackSystem.install(mm, Hypixel.kb());

        // 2. Initialize damage system
        DamageSystem.install(mm, Hypixel.dmg());

        // 3. Initialize combat system
        AttackSystem.install(mm, Vanilla18.atk());

        // Create the instance (world)
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        // Generate the world & add lighting
        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        instanceContainer.setChunkSupplier(LightingChunk::new);

        // Add an event handler to handle player spawning
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();
        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(instanceContainer);
            player.setRespawnPoint(new Pos(0, 42, 0));

            // Debug: give a known test account simulated packet lag so the laggy-landing knockback is reproducible
            // without a real high-ping client (a low-ping client clears the air clock the instant it lands).
            // /lag <ticks> tunes it at runtime on any account.
            if (player.getUsername().equals(SIM_LAG_USERNAME)) {
                lag.setDelay(player, SIM_LAG_TICKS);
                System.out.println("[sim-lag] " + player.getUsername() + " inbound packet latency = " + SIM_LAG_TICKS + " ticks");
            }

            if (player instanceof OptimizedPlayer opt) {
                opt.setPositionBroadcastInterval(2);
            }

            // Example of how to get a players protocol on login (with multiple attempts, stops once protocol is known)
            if (mm.viaProxyDetails) {
                var scheduler = MinecraftServer.getSchedulerManager();
                final int maxRuns = 3;
                final int[] runs = {0};

                scheduler.scheduleTask(() -> {
                    if (!player.isOnline()) return TaskSchedule.stop();

                    // Returns -1 until ViaVersion/proxy handshake completes
                    int protocol = mm.clientInfo().getProtocol(player);

                    if (protocol == ClientInfoService.UNKNOWN_PROTOCOL) {
                        return (++runs[0] >= maxRuns)
                                ? TaskSchedule.stop() : TaskSchedule.tick(20);
                    }
                    System.out.println(player.getUsername() + " protocol " + protocol);
                    return TaskSchedule.stop();
                }, TaskSchedule.tick(20));
            }

            player.getInventory().addItemStack(ItemStack.of(Material.WHITE_WOOL, 1000));
            player.getInventory().addItemStack(ItemStack.of(Material.DIAMOND_SWORD, 1));

            /*
            player.getAttribute(net.minestom.server.entity.attribute.Attribute.MOVEMENT_SPEED)
                    .setBaseValue(0.1 * (1 + (0.2 * 2))); // Speed II
             */
        });
        
        // Debug: /lag <ticks> sets the sender's own simulated inbound packet latency (0 = off). Lets you
        // toggle/tune the laggy-landing scenario at runtime on whichever account you're testing the victim with.
        Command lagCmd = new Command("lag");
        Argument<Integer> ticksArg = ArgumentType.Integer("ticks");
        lagCmd.setDefaultExecutor((sender, ctx) -> sender.sendMessage("usage: /lag <ticks>  (0 = off)"));
        lagCmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            int ticks = ctx.get(ticksArg);
            lag.setDelay(p, ticks);
            p.sendMessage("[sim-lag] inbound packet latency = " + ticks + " ticks");
        }, ticksArg);
        MinecraftServer.getCommandManager().register(lagCmd);

        // Start the server
        server.start("0.0.0.0", 25566);
    }
}
