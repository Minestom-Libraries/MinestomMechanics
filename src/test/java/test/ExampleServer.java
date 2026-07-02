package test;

import io.github.term4.minestommechanics.MechanicsKeys;
import io.github.term4.minestommechanics.MechanicsProfile;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.platform.fixes.FixesSystem;
import io.github.term4.minestommechanics.platform.fixes.Fixes18;
import io.github.term4.minestommechanics.platform.compatibility.Compat18;
import io.github.term4.minestommechanics.platform.compatibility.CompatConfig;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableSystem;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingSystem;
import io.github.term4.minestommechanics.mechanics.blocking.catalog.VanillaBlocking;

import net.minestom.server.entity.GameMode;
import io.github.term4.minestommechanics.tracking.ClientVersion;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import io.github.term4.minestommechanics.platform.fixes.world.FluidCountChunk;
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
import test.presets.hypixel.Hypixel;
import test.presets.customItems.PrimedTnt;
import test.presets.mmc18.Mmc18;

public class ExampleServer {
    static void main() {
        // Enable faster socket writes
        System.setProperty("minestom.new-socket-write-lock", "true");

        // Disable interaction range enforcement (mechanics lib handles reach)
        System.setProperty( "minestom.enforce-entity-interaction-range", "false");

        // Set up required flags for legacy players (prevents visual bugs on older versions)
        System.setProperty("minestom.chunk-view-distance", "12"); // less than 12 causes players to disappear at ~150 block from spawn

        // Set server TPS (default is 20, library should work with any TPS tested up to 1000)
        System.setProperty("minestom.tps", "20");

        MinecraftServer server = MinecraftServer.init(new Auth.Bungee()); // bungee auth allows 1.7 clients to join (velocity works for all later versions, and a proxy is not required)

        MinestomMechanics mm = MinestomMechanics.getInstance();
        mm.viaProxyDetails = true;
        mm.init();

        // Everything the server runs lives on one profile: the mmc18 mechanics, the general 1.8 compat layer, and the
        // legacy-client fixes. Each system below just enables itself and reads its config from here, so swapping the
        // profile swaps the whole setup.
        mm.profiles().setGlobal(Mmc18.profile().toBuilder()
                .set(MechanicsKeys.COMPAT, Compat18.config())
                .set(MechanicsKeys.FIXES, Fixes18.config())
                .build());

        AttackSystem.install(mm);
        DamageSystem.install(mm);
        KnockbackSystem.install(mm);
        ProjectileSystem.install(mm); // type enablement + the Bow launcher come from the profile's projectile config
        AttributeSystem.install(mm);
        ConsumableSystem.install(mm); // the golden-apple types come from the profile's consumable config
        BlockingSystem.install(mm);
        FixesSystem.install(mm);      // the legacy-client fix set comes from the profile's fixes config
        ExplosionSystem explosions = ExplosionSystem.install(mm); // explosion config comes from the profile (EXPLOSION key)

        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();
        instanceContainer.setExplosionSupplier(explosions.supplier()); // route instance.explode(...) through the system

        // Generate the world & add lighting
        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        instanceContainer.setChunkSupplier(FluidCountChunk::new);

        // Test damage types
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

        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();

        // TNT test item: placing TNT spawns a primed-TNT entity (falls + fuses + detonates power 4) instead of a block
        globalEventHandler.addListener(PlayerBlockPlaceEvent.class, event -> {
            if (!event.getBlock().compare(Block.TNT)) return;
            event.setCancelled(true);
            Player p = event.getPlayer();
            if (p.getInstance() == null || explosions == null) return;
            Point at = event.getBlockPosition();
            PrimedTnt.spawn(explosions, p.getInstance(), new Pos(at.blockX() + 0.5, at.blockY(), at.blockZ() + 0.5));
            if (p.getGameMode() != GameMode.CREATIVE) { // cancelling the place keeps the item, so consume one like vanilla TNT
                ItemStack held = p.getItemInHand(event.getHand());
                p.setItemInHand(event.getHand(), held.withAmount(held.amount() - 1));
            }
        });

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

                    if (protocol == ClientVersion.UNKNOWN_PROTOCOL) {
                        return (++runs[0] >= maxRuns)
                                ? TaskSchedule.stop() : TaskSchedule.tick(20);
                    }
                    System.out.println(player.getUsername() + " protocol " + protocol);
                    return TaskSchedule.stop();
                }, TaskSchedule.tick(20));
            }

            player.getInventory().addItemStack(ItemStack.of(Material.RED_WOOL, 1000));
            player.getInventory().addItemStack(ItemStack.of(Material.LADDER, 64));
            player.getInventory().addItemStack(ItemStack.of(Material.DIAMOND_SWORD, 1));
            player.getInventory().addItemStack(ItemStack.of(Material.SNOWBALL, 64));
            player.getInventory().addItemStack(ItemStack.of(Material.FIRE_CHARGE, 64)); // right-click to throw a fireball
            player.getInventory().addItemStack(ItemStack.of(Material.TNT, 64)); // place to spawn a primed TNT (power 4, ~50t fuse)
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

        // Full diamond armor to test 1.8 armor visibility to other clients (the spawn-with-armor-already-set case). Applied on
        // FIRST SPAWN, not AsyncPlayerConfigurationEvent: Minestom updates an item's attribute modifiers (ARMOR, ...) only for
        // the inventory's viewers, and a player becomes a viewer of its OWN inventory in UNSAFE_init - after the config event.
        // So armor set during config is visible but grants 0 armor until re-equipped; by PlayerSpawnEvent the player is a viewer.
        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            Player player = event.getPlayer();
            player.setHelmet(ItemStack.of(Material.DIAMOND_HELMET));
            player.setChestplate(ItemStack.of(Material.DIAMOND_CHESTPLATE));
            player.setLeggings(ItemStack.of(Material.DIAMOND_LEGGINGS));
            player.setBoots(ItemStack.of(Material.DIAMOND_BOOTS));
        });

        // ─── DEV / TEST TOOLS — delete this call + the DEV TOOLS section at the bottom of this file for production ───
        installDevTools(mm);

        // Start the server
        server.start("0.0.0.0", 25566);
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

    // ================================================================================================================
    //  DEV / TEST TOOLS  —  in-game testing commands + the inbound-lag simulator. Delete this whole section (and the
    //  installDevTools(mm) call in main) for production; LagSimulator.java is then unused and can also be deleted.
    // ================================================================================================================

    private static void installDevTools(MinestomMechanics mm) {
        // test-only inbound-lag simulator (delays a player's movement packets so the server perceives their
        // position/onGround late, like a high-ping client). Purely test-side - mechanics is untouched.
        final LagSimulator lag = new LagSimulator();
        lag.install();

        // /lag <ticks> sets the sender's own simulated inbound packet latency (0 = off).
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

        // /gmc, /gms: quick gamemode toggles
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

        // /compat: toggle this player between the global 1.8 profile and a per-player modern override (mode switch demo).
        Command compatCmd = new Command("compat");
        compatCmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof OptimizedPlayer op)) return;
            boolean turningOff = mm.profiles().player(op) == null; // no per-player override = currently on the global mmc18 profile
            if (turningOff) {
                // A per-player override to modern. The full reset-then-layer apply (PlayerConfigApplier, via the profile-change
                // hook) switches the player cleanly off 1.8 - no manual undo of sticky knobs / attack cooldown needed.
                mm.profiles().setPlayer(op, MechanicsProfile.builder().set(MechanicsKeys.COMPAT, Compat18.off()).build());
                op.sendMessage("Mechanics set to latest version");
            } else {
                // Drop the override -> fall back to the global mmc18 profile; the apply restores the full 1.8 set.
                mm.profiles().setPlayer(op, null);
                op.sendMessage("Mechanics set to 1.8");
            }
        });
        MinecraftServer.getCommandManager().register(compatCmd);

        // /shorts: toggle nativeShortVelocity for this player (A/B the byte-exact Animatium velocity vs the lossy LpVec3). A
        // profile change re-pushes the Animatium feature set, so it flips live - take a >2 b/t knockback to see a difference.
        Command shortsCmd = new Command("shorts");
        shortsCmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof OptimizedPlayer op)) return;
            CompatConfig current = mm.profiles().resolve(op, MechanicsKeys.COMPAT);
            boolean on = !(current != null && Boolean.TRUE.equals(current.nativeShortVelocity));
            mm.profiles().setPlayer(op, MechanicsProfile.builder()
                    .set(MechanicsKeys.COMPAT, Compat18.config().toBuilder().nativeShortVelocity(on).build())
                    .build());
            op.sendMessage("Shorts velocity: " + (on ? "ON" : "OFF") + " (take a >2 b/t knockback to compare)");
        });
        MinecraftServer.getCommandManager().register(shortsCmd);

        // /suffocate: drops a stone block into your head to test suffocation damage (be in survival - /gms)
        Command suffocate = new Command("suffocate");
        suffocate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p) || p.getInstance() == null) return;
            p.getInstance().setBlock(p.getPosition().add(0, p.getEyeHeight(), 0), Block.STONE);
            p.sendMessage("[suffocate] stone placed in your head (survival = 1/tick suffocation)");
        });
        MinecraftServer.getCommandManager().register(suffocate);

        // /explode [power]: explosion at your feet (no source, so you feel the falloff knockback + take the damage)
        ExplosionSystem explosions = mm.module(ExplosionSystem.class);
        Command explode = new Command("explode");
        Argument<Integer> explodePower = ArgumentType.Integer("power");
        explode.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null && explosions != null)
                explosions.explode(p.getInstance(), p.getPosition(), 4.0f);
        });
        explode.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null && explosions != null)
                explosions.explode(p.getInstance(), p.getPosition(), (float) (int) ctx.get(explodePower));
        }, explodePower);
        MinecraftServer.getCommandManager().register(explode);

        // /explodeat <dx> <dy> <dz> [power]: detonate at your feet + offset (power 2 = fireball), to DECOUPLE detonation
        // height from distance for the head-gate model test - e.g. /explodeat 0 2 0 (straight up: high det.y, short dist)
        // vs /explodeat 3 1 0 (far + chest height: low det.y, long dist). Watch the [head-gate] debug line + whether you move.
        Command explodeAt = new Command("explodeat");
        Argument<Double> exAtX = ArgumentType.Double("dx");
        Argument<Double> exAtY = ArgumentType.Double("dy");
        Argument<Double> exAtZ = ArgumentType.Double("dz");
        Argument<Double> exAtPower = ArgumentType.Double("power");
        explodeAt.setDefaultExecutor((sender, ctx) -> sender.sendMessage("usage: /explodeat <dx> <dy> <dz> [power]  (power default 2 = fireball)"));
        explodeAt.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null && explosions != null)
                explosions.explode(p.getInstance(), p.getPosition().add(ctx.get(exAtX), ctx.get(exAtY), ctx.get(exAtZ)), 2.0f);
        }, exAtX, exAtY, exAtZ);
        explodeAt.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null && explosions != null)
                explosions.explode(p.getInstance(), p.getPosition().add(ctx.get(exAtX), ctx.get(exAtY), ctx.get(exAtZ)), ctx.get(exAtPower).floatValue());
        }, exAtX, exAtY, exAtZ, exAtPower);
        MinecraftServer.getCommandManager().register(explodeAt);

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

        // /enchant <enchantment> [level]: enchant the held item (e.g. /enchant fire_aspect 2, /enchant sharpness 5).
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

        // /effect <effect> [strength] [seconds]  |  /effect clear: apply a potion effect (e.g. /effect strength 2).
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

        // /velcap (LpVec3 snap) vs /velcapbridge (exact 1.8 short): A/B for the LegacyVelocityBridge knockback path
        EventNode<@NotNull PlayerEvent> playerNode = EventNode.type("mm:test-player", EventFilter.PLAYER);
        VelocityCapTestCommands.install(playerNode);
        MinecraftServer.getGlobalEventHandler().addChild(playerNode);

        Command velCap = new Command("velcap");
        velCap.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) VelocityCapTestCommands.applyNormalCap(p);
        });
        MinecraftServer.getCommandManager().register(velCap);

        Command velCapBridge = new Command("velcapbridge");
        velCapBridge.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) VelocityCapTestCommands.applyBridgeCap(p);
        });
        MinecraftServer.getCommandManager().register(velCapBridge);
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
}
