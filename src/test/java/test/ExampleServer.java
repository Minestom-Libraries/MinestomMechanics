package test;

import io.github.term4.minestommechanics.MechanicsProfile;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.Vanilla18;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.mechanics.attack.reach.ReachLog;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileConfig;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.platform.fixes.FixesSystem;
import io.github.term4.minestommechanics.platform.fixes.FixesConfig;
import io.github.term4.minestommechanics.platform.fixes.visuals.VisualsConfig;
import io.github.term4.minestommechanics.platform.fixes.visuals.legacy_1_8.LegacyArrowVisibilityConfig;
import io.github.term4.minestommechanics.platform.fixes.client.SelfPlacementFixConfig;
import io.github.term4.minestommechanics.platform.fixes.world.BlockPlacementFixConfig;
import io.github.term4.minestommechanics.mechanics.projectile.shootables.Bow;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableSystem;
import io.github.term4.minestommechanics.mechanics.consumable.catalog.VanillaConsumables;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingSystem;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingBehavior;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingTypeConfig;
import io.github.term4.minestommechanics.mechanics.blocking.catalog.VanillaBlocking;

import net.minestom.server.entity.GameMode;
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
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.timer.TaskSchedule;
import test.presets.Hypixel;
import test.presets.mmc18;

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
        KnockbackSystem.install(mm, mmc18.kb());

        // 2. Initialize damage system.
        DamageSystem.install(mm, mmc18.dmg());

        // 3. Initialize combat system
        AttackSystem.install(mm, mmc18.atk());
        ReachLog.install(mm, 4.0); // log reach per hit + synchronously cancel only hits whose lower-bound reach > 4.0 (well above the ~3.6 knockback double-hit artifacts)

        // 4. Initialize projectile system
        ProjectileSystem.install(mm, ProjectileConfig.builder(mmc18.projectiles())
                .build(), new Bow());

        // 5b. Initialize the attribute/potion system (enchant + potion gameplay).
        AttributeSystem.install(mm, Vanilla18.attributes());

        // 5c. Item registry: held-weapon/tool stats (the melee base damage reads this).
        mm.registerItems(Vanilla18.items());

        // 5d. Consumables (eat/drink over time): the golden apples, with 1.8-source effects.
        ConsumableSystem.install(mm, Vanilla18.consumables(), VanillaConsumables.types());

        // 5e. Blocking: 1.8 sword block (SWORD behavior, swords from Vanilla18.blocking()) + a 26-style shield entry.
        BlockingSystem.install(mm, Vanilla18.blocking().toBuilder()
                .material(Material.SHIELD, BlockingTypeConfig.builder()
                        .behavior(BlockingBehavior.SHIELD)
                        .reductionBase(0.0).reductionFactor(1.0)   // full block within the arc
                        .blockDelayTicks(5).blockingAngle(100.0)
                        .build())
                .build());

        // 5. Initialize the client/protocol fixes system.
        FixesSystem.install(mm, FixesConfig.builder()
                .visuals(VisualsConfig.builder()
                        .legacyArrowVisibility(LegacyArrowVisibilityConfig.builder().enabled(true).deflectParticles(true).build())
                        .build())
                .blockPlacement(BlockPlacementFixConfig.builder().enabled(true).build()) // TODO: Remove after PR is merged to minestom
                .selfPlacement(SelfPlacementFixConfig.builder().enabled(true).build()) // For 1.8 clients so they can place ladders while climbing up
                .build());

        // Scoped mechanics (player -> instance -> global)
        mm.profiles().setGlobal(MechanicsProfile.builder()
                .player(mmc18.player())
                .velocity(mmc18.velocity())
                .compat(mmc18.compat())
                .build());

        // Create the instance (world)
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        // Generate the world & add lighting
        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        instanceContainer.setChunkSupplier(LightingChunk::new);

        // Test dammage types
        instanceContainer.loadChunk(1, 0).thenRun(() -> {
            for (int x = 18; x <= 20; x++)
                for (int z = 0; z <= 2; z++)
                    for (int y = 40; y <= 46; y++)
                        instanceContainer.setBlock(x, y, z, Block.WATER);
            instanceContainer.setBlock(25, 40, 0, Block.FIRE);
            instanceContainer.setBlock(25, 40, 1, Block.FIRE);
            instanceContainer.setBlock(29, 39, 0, Block.SAND);
            instanceContainer.setBlock(29, 40, 0, Block.CACTUS);
            instanceContainer.setBlock(29, 41, 0, Block.CACTUS);
        });

        // Water FLOW-push test pads: 4 enclosed lanes, each with a hand-set `level` gradient so the current flows
        // a known cardinal direction (Minestom does not spread fluids, so we set the levels manually). Stand in a
        // lane feet-in-water on the floor and take a hit: the folded knockback should carry the flow term (the
        // VelocityConfig.flowPush residual) in the AWAY-from-the-wool direction. Compare to vanilla 1.8.
        instanceContainer.loadChunk(0, 0).thenRun(() -> {
            buildFlowLane(instanceContainer,  2, 40,  4,  1,  0, 7, Block.RED_WOOL);    // EAST  (+X)
            buildFlowLane(instanceContainer,  8, 40,  7, -1,  0, 7, Block.BLUE_WOOL);   // WEST  (-X)
            buildFlowLane(instanceContainer, 12, 40,  4,  0,  1, 7, Block.YELLOW_WOOL); // SOUTH (+Z)
            buildFlowLane(instanceContainer, 14, 40, 10,  0, -1, 7, Block.LIME_WOOL);   // NORTH (-Z)
            System.out.println("[flow-test] lanes @ y=40 (flow points AWAY from the wool marker): "
                    + "EAST/+X red src(2,4) | WEST/-X blue src(8,7) | SOUTH/+Z yellow src(12,4) | NORTH/-Z green src(14,10)");
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

            //player.setGameMode(GameMode.CREATIVE);

            player.getInventory().addItemStack(ItemStack.of(Material.RED_WOOL, 1000));
            player.getInventory().addItemStack(ItemStack.of(Material.LADDER, 64));
            player.getInventory().addItemStack(ItemStack.of(Material.DIAMOND_SWORD, 1));
            player.getInventory().addItemStack(ItemStack.of(Material.SNOWBALL, 64));
            player.getInventory().addItemStack(ItemStack.of(Material.EGG, 16));
            player.getInventory().addItemStack(ItemStack.of(Material.ENDER_PEARL, 16));
            player.getInventory().addItemStack(ItemStack.of(Material.BOW, 1));
            player.getInventory().addItemStack(ItemStack.of(Material.ARROW, 64));
            player.getInventory().addItemStack(ItemStack.of(Material.GOLDEN_APPLE, 16));
            player.getInventory().addItemStack(ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE, 16));
            player.getInventory().addItemStack(ItemStack.of(Material.MILK_BUCKET, 1));
            // a drinkable Speed potion (custom effect, so it works without extending VanillaPotions)
            player.getInventory().addItemStack(ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS,
                    new PotionContents(new CustomPotionEffect(PotionEffect.SPEED, 0, 1200, false, true, true))));

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

        // /gmc, /gms: quick gamemode toggles for in-game testing
        Command gmc = new Command("gmc");
        gmc.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) { p.setGameMode(GameMode.CREATIVE); p.sendMessage("gamemode: creative"); }
        });
        MinecraftServer.getCommandManager().register(gmc);

        Command gms = new Command("gms");
        gms.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) { p.setGameMode(GameMode.SURVIVAL); p.sendMessage("gamemode: survival"); }
        });
        MinecraftServer.getCommandManager().register(gms);

        // /suffocate: drops a stone block into your head to test suffocation damage (be in survival - /gms)
        Command suffocate = new Command("suffocate");
        suffocate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p) || p.getInstance() == null) return;
            p.getInstance().setBlock(p.getPosition().add(0, p.getEyeHeight(), 0), Block.STONE);
            p.sendMessage("[suffocate] stone placed in your head (survival = 1/tick suffocation)");
        });
        MinecraftServer.getCommandManager().register(suffocate);

        // /sword: gives a blockable diamond sword (right-click + hold to block; 1.8 = (1+f)*0.5 damage, pre-armor)
        Command sword = new Command("sword");
        sword.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.item(Material.DIAMOND_SWORD));
                p.sendMessage("[sword] blockable diamond sword given (hold right-click to block)");
            }
        });
        MinecraftServer.getCommandManager().register(sword);

        // /nbsword: a non-blockable diamond sword (opted out) - should NOT reduce damage even while right-click held
        Command nbsword = new Command("nbsword");
        nbsword.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.nonBlocking(ItemStack.of(Material.DIAMOND_SWORD)));
                p.sendMessage("[nbsword] non-blockable diamond sword given (holding right-click should not block)");
            }
        });
        MinecraftServer.getCommandManager().register(nbsword);

        // /shield: gives a shield (SHIELD behavior - directional frontal arc + 0.25s block delay; best on a 1.9+ client)
        Command shield = new Command("shield");
        shield.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.item(Material.SHIELD));
                p.sendMessage("[shield] shield given (hold right-click; blocks from the front after a short delay)");
            }
        });
        MinecraftServer.getCommandManager().register(shield);

        // /enchant <enchantment> [level]: enchant the held item, to test combat/defense enchants in game
        // (e.g. /enchant fire_aspect 2, /enchant protection 4, /enchant sharpness 5). Reads the adventure key our
        // catalog sources match on; namespace defaults to minecraft:.
        Command enchant = new Command("enchant");
        Argument<String> enchName = ArgumentType.String("enchantment");
        Argument<Integer> enchLevel = ArgumentType.Integer("level");
        enchant.setDefaultExecutor((sender, ctx) ->
                sender.sendMessage("usage: /enchant <enchantment> [level]  (e.g. /enchant fire_aspect 2)"));
        enchant.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEnchant(p, ctx.get(enchName), 1);
        }, enchName);
        enchant.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEnchant(p, ctx.get(enchName), ctx.get(enchLevel));
        }, enchName, enchLevel);
        MinecraftServer.getCommandManager().register(enchant);

        // /effect <effect> [strength] [seconds]  |  /effect clear: apply a potion effect to the sender, to test the
        // potion catalog in game (e.g. /effect strength 2, /effect resistance 1 60, /effect speed, /effect clear).
        // strength = level (1 = level I); seconds default 30. Namespace defaults to minecraft:.
        Command effectCmd = new Command("effect");
        Argument<String> effName = ArgumentType.String("effect");
        Argument<Integer> effStrength = ArgumentType.Integer("strength");
        Argument<Integer> effSeconds = ArgumentType.Integer("seconds");
        effectCmd.setDefaultExecutor((sender, ctx) ->
                sender.sendMessage("usage: /effect <effect> [strength] [seconds]  |  /effect clear"));
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), 1, 30);
        }, effName);
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), ctx.get(effStrength), 30);
        }, effName, effStrength);
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), ctx.get(effStrength), ctx.get(effSeconds));
        }, effName, effStrength, effSeconds);
        MinecraftServer.getCommandManager().register(effectCmd);

        // Start the server
        server.start("0.0.0.0", 25566);
    }

    /** Applies (or clears) a potion effect on the sender for in-game testing. {@code strength} is the level (1 = level I); duration in seconds. */
    private static void applyEffect(Player p, String name, int strength, int seconds) {
        if (name.equalsIgnoreCase("clear")) { p.clearEffects(); p.sendMessage("cleared effects"); return; }
        PotionEffect effect = PotionEffect.fromKey(name.contains(":") ? name : "minecraft:" + name);
        if (effect == null) { p.sendMessage("unknown effect: " + name); return; }
        int amplifier = Math.max(0, strength - 1); // strength 1 -> amplifier 0 (level I)
        p.addEffect(new Potion(effect, amplifier, seconds * 20));
        p.sendMessage("applied " + effect.key().asString() + " " + strength + " for " + seconds + "s");
    }

    /** Adds {@code name} (defaulting the namespace to minecraft:) at {@code level} to the player's held item's enchantments. */
    private static void applyEnchant(Player p, String name, int level) {
        ItemStack held = p.getItemInMainHand();
        if (held.isAir()) { p.sendMessage("hold an item first"); return; }
        Key key = Key.key(name.contains(":") ? name : "minecraft:" + name);
        RegistryKey<Enchantment> ench = RegistryKey.unsafeOf(key);
        EnchantmentList existing = held.get(DataComponents.ENCHANTMENTS);
        EnchantmentList updated = (existing != null ? existing : EnchantmentList.EMPTY).with(ench, level);
        p.setItemInMainHand(held.with(DataComponents.ENCHANTMENTS, updated));
        p.sendMessage("enchanted held item with " + key.asString() + " " + level);
    }

    /**
     * Builds a 1-wide enclosed water channel with a manual {@code level} gradient (source/marker end = level 0,
     * rising along {@code (dirX,dirZ)}) so the water flows toward the far end - a deterministic flow-push test pad.
     * Stone floor + side/end walls isolate the lane; the player stands feet-in-water on the floor (the verified
     * standing {@code -0.02} motY case), so only the horizontal flow term folds into a hit.
     */
    private static void buildFlowLane(InstanceContainer inst, int sx, int baseY, int sz,
                                      int dirX, int dirZ, int length, Block marker) {
        int pdx = dirZ, pdz = -dirX; // perpendicular = side-wall offset
        for (int i = -1; i <= length; i++) { // -1 and length = solid end caps
            int cx = sx + dirX * i, cz = sz + dirZ * i;
            boolean cap = i < 0 || i >= length;
            Block fill = cap ? Block.STONE : Block.WATER.withProperty("level", Integer.toString(Math.min(i, 7)));
            inst.setBlock(cx, baseY - 1, cz, Block.STONE); // floor
            inst.setBlock(cx, baseY, cz, fill);            // feet
            inst.setBlock(cx, baseY + 1, cz, fill);        // body
            inst.setBlock(cx, baseY + 2, cz, Block.AIR);   // headroom
            for (int s = -1; s <= 1; s += 2) {             // side walls (solid -> no spurious side flow)
                int wx = cx + pdx * s, wz = cz + pdz * s;
                inst.setBlock(wx, baseY - 1, wz, Block.STONE);
                inst.setBlock(wx, baseY, wz, Block.STONE);
                inst.setBlock(wx, baseY + 1, wz, Block.STONE);
            }
        }
        inst.setBlock(sx, baseY + 2, sz, marker); // flow points AWAY from this marker
    }
}
