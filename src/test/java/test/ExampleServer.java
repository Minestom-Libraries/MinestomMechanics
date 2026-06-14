package test;

import io.github.term4.minestommechanics.MechanicsProfile;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.api.event.DamageEvent;
import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.playerattack.PlayerAttack;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.attribute.Attribute;
import io.github.term4.minestommechanics.tracking.ClientInfoTracker;
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
import test.presets.Minemen;

public class ExampleServer {
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

        // Keep-alive cadence = how often Player#getLatency() refreshes (the round-trip itself is measured
        // read-thread/sub-tick accurate). Vanilla 1.8's 2s here; pass e.g. 500 for Hypixel's probe rate.
        MinestomMechanics.keepAliveInterval(MinestomMechanics.LEGACY_KEEP_ALIVE_MS);

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

        // 1. Initialize knockback system
        KnockbackSystem.install(mm, Minemen.kb());

        // 2. Initialize damage system.
        DamageSystem.install(mm, Minemen.dmg());

        // 3. Initialize combat system
        AttackSystem.install(mm, Minemen.atk());

        // 4. Initialize projectile system (config decides what runs, like the damage system). Minemen.projectiles()
        // currently inherits the vanilla 1.8 projectile baseline (physics + snowball KB/damage live in the presets).
        ProjectileSystem.install(mm, Minemen.projectiles());

        // Debug (temporary)
        MinecraftServer.getGlobalEventHandler().addListener(DamageEvent.class, event -> {
            if (!PlayerAttack.KEY.equals(event.type().key())) return;
            if (!(event.target() instanceof Player victim)) return;
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                if (victim.isOnline() && !victim.isDead())
                    victim.setHealth((float) victim.getAttributeValue(Attribute.MAX_HEALTH));
            });
        });

        // Scoped mechanics (player -> instance -> global). The velocity tracking method lives here - the melee
        // friction fold, the hurt broadcast, AND projectile knockback all read MechanicsProfile.velocity for the
        // victim (Minemen.kb() no longer pins its own; a per-config KnockbackConfig.velocity override would still
        // win if set). Vanilla18.player(): broadcast position every 2 ticks (1.8 tracker frequency).
        mm.profiles().setGlobal(MechanicsProfile.builder()
                .player(Vanilla18.player())
                .velocity(Minemen.velocity())
                .build());

        // Create the instance (world)
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        // Generate the world & add lighting
        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        instanceContainer.setChunkSupplier(LightingChunk::new);

        // Test hazards near spawn (surface is y=40): a lava pool, a fire patch, and a cactus.
        // Fall damage: pillar up with the wool and jump off.
        instanceContainer.loadChunk(1, 0).thenRun(() -> {
            for (int x = 18; x <= 20; x++)
                for (int z = 0; z <= 2; z++)
                    instanceContainer.setBlock(x, 40, z, Block.LAVA);
            instanceContainer.setBlock(25, 40, 0, Block.FIRE);
            instanceContainer.setBlock(25, 40, 1, Block.FIRE);
            instanceContainer.setBlock(29, 39, 0, Block.SAND);
            instanceContainer.setBlock(29, 40, 0, Block.CACTUS);
            instanceContainer.setBlock(29, 41, 0, Block.CACTUS);
        });

        // Add an event handler to handle player spawning
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();
        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(instanceContainer);
            player.setRespawnPoint(new Pos(0, 42, 0));

            // Example of how to get a players protocol on login (with multiple attempts, stops once protocol is known)
            if (mm.viaProxyDetails) {
                var scheduler = MinecraftServer.getSchedulerManager();
                final int maxRuns = 3;
                final int[] runs = {0};

                scheduler.scheduleTask(() -> {
                    if (!player.isOnline()) return TaskSchedule.stop();

                    // Returns -1 until ViaVersion/proxy handshake completes
                    int protocol = mm.clientInfo().getProtocol(player);

                    if (protocol == ClientInfoTracker.UNKNOWN_PROTOCOL) {
                        return (++runs[0] >= maxRuns)
                                ? TaskSchedule.stop() : TaskSchedule.tick(20);
                    }
                    System.out.println(player.getUsername() + " protocol " + protocol);
                    return TaskSchedule.stop();
                }, TaskSchedule.tick(20));
            }

            player.setGameMode(GameMode.CREATIVE);

            player.getInventory().addItemStack(ItemStack.of(Material.WHITE_WOOL, 1000));
            player.getInventory().addItemStack(ItemStack.of(Material.DIAMOND_SWORD, 1));
            player.getInventory().addItemStack(ItemStack.of(Material.SNOWBALL, 64));
            player.getInventory().addItemStack(ItemStack.of(Material.EGG, 16));
            player.getInventory().addItemStack(ItemStack.of(Material.ENDER_PEARL, 16));
            player.getInventory().addItemStack(ItemStack.of(Material.BOW, 1));
            player.getInventory().addItemStack(ItemStack.of(Material.ARROW, 64));



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
