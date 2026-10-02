package com.nstut.endless.testing;

import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Native client packets and survival item uses, with authoritative results and conserved stock. */
public final class LiveCreatePlayerWorkflowServerTest {
    public static final String PROPERTY = "endless.liveCreatePlayerWorkflows";
    public static final int[] HEIGHTS = {64, -65, 319, -513, 511, -2049, 2047, -1_000_449, 1_000_447};
    private static int lane, step, ticks;
    private static boolean prepared, done;
    public static BlockPos base(int lane) { return new BlockPos(34, HEIGHTS[lane], 34); }
    public static BlockPos flag(int lane) { return base(lane).west(2); }
    private LiveCreatePlayerWorkflowServerTest() {}
    public static void tick(MinecraftServer server) {
        if (done || !Boolean.getBoolean(PROPERTY) || server.getPlayerList().getPlayers().isEmpty()) return;
        var level = server.overworld(); var player = server.getPlayerList().getPlayers().get(0);
        try {
            if (!prepared) {
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
                for (Entity mob : level.getAllEntities()) if (mob instanceof net.minecraft.world.entity.Mob) mob.discard();
                level.setChunkForced(2, 2, true);
                if (!level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(2, 2))) return;
                BlockPos b = base(lane);
                for (BlockPos p : BlockPos.betweenClosed(b.offset(-3, -1, -3), b.offset(9, 5, 5)))
                    level.setBlock(p, p.getY() == b.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(b, block("brown_toolbox"), 3);
                Object inv = field(level.getBlockEntity(b), "inventory");
                inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(inv, 0, new ItemStack(Items.IRON_INGOT, 64));
                level.setBlock(b.east(2), block("copycat_panel"), 3);
                level.setBlock(b.east(4), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(b.south(2), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(b.south(3), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(b.east(8), Blocks.STONE.defaultBlockState(), 3);
                player.setGameMode(GameType.SURVIVAL); player.setNoGravity(false);
                player.getInventory().clearContent();
                player.getInventory().setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 3));
                player.getInventory().setItem(2, item("wand_of_symmetry"));
                player.getInventory().setItem(3, new ItemStack(Items.WHITE_WOOL, 8));
                player.getInventory().setItem(4, item("andesite_scaffolding"));
                player.inventoryMenu.broadcastChanges();
                player.teleportTo(b.getX() + 1.5, b.getY(), b.getZ() + .5);
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                player.setYRot(0); player.setYHeadRot(0);
                level.setBlock(flag(lane), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                prepared = true; ticks = 0; return;
            }
            require(++ticks < 600, "player workflow timed out lane=" + lane + " step=" + step);
            CompoundTag persistent = (CompoundTag) player.getClass().getMethod("getPersistentData").invoke(player);
            CompoundTag link = persistent.getCompound("CreateToolboxData");
            int stock = 0;
            if (step < 2) {
                Object inv = field(level.getBlockEntity(base(lane)), "inventory");
                for (int i = 0; i < 32; i++) {
                    ItemStack stack = (ItemStack) inv.getClass().getMethod("getStackInSlot", int.class).invoke(inv, i);
                    require(stack.isEmpty() || stack.is(Items.IRON_INGOT), "toolbox stock changed identity"); stock += stack.getCount();
                }
            }
            if (step == 0 && link.contains("0") && player.getInventory().getItem(0).getCount() == 32) {
                require(LiveCreateNbt.readPos(link.getCompound("0"), "Pos").equals(base(lane)), "toolbox packet selected an aliased height");
                require(stock == 32 && player.getInventory().getItem(0).is(Items.IRON_INGOT), "toolbox replenishment lost or duplicated stock");
                step = 1; level.setBlock(flag(lane), Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
            } else if (step == 1 && !link.contains("0") && player.getInventory().getItem(0).isEmpty()) {
                require(stock == 64, "native unequip failed to return exact toolbox stock");
                step = 2; level.setBlock(flag(lane), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            } else if (step == 2) {
                var copycat = level.getBlockEntity(base(lane).east(2));
                var material = (net.minecraft.world.level.block.state.BlockState) copycat.getClass().getMethod("getMaterial").invoke(copycat);
                if (!material.is(Blocks.SPRUCE_PLANKS) || !level.getBlockState(base(lane).east(4).above()).is(block("andesite_scaffolding").getBlock())) return;
                require(player.getInventory().getItem(1).getCount() == 2 && player.getInventory().getItem(4).isEmpty(), "survival copycat/scaffolding did not consume exact materials");
                step = 3; level.setBlock(flag(lane), Blocks.LAPIS_BLOCK.defaultBlockState(), 3);
            } else if (step == 3 && LiveCreateNbt.symmetryEnabled(player.getInventory().getItem(2))) {
                step = 4; level.setBlock(flag(lane), Blocks.WHITE_WOOL.defaultBlockState(), 3);
            } else if (step == 4 && level.getBlockState(base(lane).south(3).above()).is(Blocks.WHITE_WOOL) && level.getBlockState(base(lane).south().above()).is(Blocks.WHITE_WOOL)) {
                // Create replaces the held inventory stack for its reservation and
                // mirrored block. Vanilla's later shrink targets the old stack reference.
                require(player.getInventory().getItem(3).getCount() == 6, "symmetry changed native survival material accounting remaining=" + player.getInventory().getItem(3).getCount());
                player.getInventory().setItem(2, ItemStack.EMPTY); // Finish symmetry before the independent reach control.
                player.getInventory().setItem(5, new ItemStack(Items.WHITE_WOOL, 8));
                level.setBlock(flag(lane), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                step = 5; ticks = 0;
            } else if (step == 5 && ticks >= 40) {
                require(level.getBlockState(base(lane).east(8).above()).isAir() && player.getInventory().getItem(5).getCount() == 8, "ordinary survival reach admitted the distant negative-control placement");
                player.teleportTo(base(lane).getX() + 1.5, base(lane).getY(), base(lane).getZ() + .5);
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, item("extendo_grip"));
                level.setBlock(flag(lane), Blocks.NETHERITE_BLOCK.defaultBlockState(), 3); step = 6; ticks = 0;
            } else if (step == 6 && level.getBlockState(base(lane).east(8).above()).is(Blocks.WHITE_WOOL)) {
                require(player.getInventory().getItem(5).getCount() == 7 && player.getOffhandItem().getDamageValue() == 1, "extendo placement failed survival stock or native durability accounting");
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                // A sealed, water-filled cell keeps the natural player eye-fluid test underwater.
                BlockPos b = base(lane);
                for (BlockPos p : BlockPos.betweenClosed(b.offset(0, 0, -2), b.offset(3, 3, 2))) {
                    boolean wall = p.getX() == b.getX() || p.getX() == b.getX()+3 || p.getZ() == b.getZ()-2 || p.getZ() == b.getZ()+2 || p.getY() == b.getY()+3;
                    level.setBlock(p, wall ? Blocks.GLASS.defaultBlockState() : Blocks.WATER.defaultBlockState(), 3);
                }
                player.teleportTo(b.getX()+1.5, b.getY(), b.getZ()+.5); player.setAirSupply(player.getMaxAirSupply());
                level.setBlock(flag(lane), Blocks.BLUE_WOOL.defaultBlockState(), 3); step = 7; ticks = 0;
            } else if (step == 7 && ticks >= 40) {
                require(player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && player.getAirSupply() < player.getMaxAirSupply(), "underwater negative control did not lose breathing air");
                ItemStack tank = item("copper_backtank"); LiveCreateNbt.setBacktankAir(tank, 20);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, tank);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, item("copper_diving_helmet"));
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, item("copper_diving_boots"));
                step = 8; ticks = 0;
            } else if (step == 8 && ticks >= 60) {
                int remaining = ((Number) Class.forName("com.simibubi.create.content.equipment.armor.BacktankUtil").getMethod("getAir", ItemStack.class).invoke(null, player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST))).intValue();
                require(player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && player.getAirSupply() == player.getMaxAirSupply() && remaining == 17, "native diving failed to refill player air or consume three seconds of tank air: remaining=" + remaining + " playerAir=" + player.getAirSupply());
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, ItemStack.EMPTY);
                LiveCreateStockWorkflowTest.prepare(level, player, base(lane));
                level.setBlock(flag(lane), Blocks.GRAY_WOOL.defaultBlockState(), 3); step = 9; ticks = 0;
            } else if (step == 9 && ticks >= 40) {
                LiveCreateStockWorkflowTest.assertStock(level, base(lane), 14);
                level.setBlock(flag(lane), Blocks.ORANGE_WOOL.defaultBlockState(), 3); step = 10; ticks = 0;
            } else if (step == 10 && LiveCreateStockWorkflowTest.unpack(level, base(lane), 7)) {
                LiveCreateStockWorkflowTest.trigger(level, base(lane)); step = 11; ticks = 0;
            } else if (step == 11 && LiveCreateStockWorkflowTest.unpack(level, base(lane), 14)) {
                LiveCreateStockWorkflowTest.assertRequester(level, base(lane));
                LiveCreateStockWorkflowTest.prepareRepackager(level, base(lane)); step = 13; ticks = 0;
            } else if (step == 13 && ticks >= 40) {
                LiveCreateStockWorkflowTest.completeRepackager(level, base(lane)); step = 14; ticks = 0;
            } else if (step == 14 && LiveCreateStockWorkflowTest.verifyRepackager(level, base(lane))) {
                LiveCreateStockWorkflowTest.prepareGauge(level, base(lane)); step = 15; ticks = 0;
            } else if (step == 15 && ticks >= 40) {
                LiveCreateStockWorkflowTest.supplyGauge(level, base(lane)); step = 16; ticks = 0;
            } else if (step == 16 && LiveCreateStockWorkflowTest.unpackGauge(level, base(lane))) {
                step = 17; ticks = 0;
            } else if (step == 17 && ticks >= 40) {
                LiveCreateStockWorkflowTest.verifyGauge(level, base(lane));
                System.out.println("ENDLESS_CREATE_PLAYER_CASE_PASS y=" + HEIGHTS[lane] + " toolboxPackets=true exactStock64=true copycat=true scaffolding=true symmetry=true extendoControl=true divingControl=true tankAirConsumed=3 stockTickerPacket=true redstoneRequester=true orderedDiamondsConserved=14 repackagerIncompleteControl=true fragmentsRepackedAndUnpacked=7 factoryGaugeEmptyNetworkControl=true factoryGaugeRestocked=7 finalDiamonds=28");
                level.setBlock(flag(lane), Blocks.LIME_WOOL.defaultBlockState(), 3); step = 12; ticks = 0;
            } else if (step == 12 && ticks > 20) {
                // Allow the client to observe the acknowledgement before moving to the next origin.
                if (++lane == HEIGHTS.length) { done = true; System.out.println("ENDLESS_CREATE_PLAYER_SERVER_PASS cases=" + HEIGHTS.length); }
                else { prepared = false; step = ticks = 0; }
            }
            player.inventoryMenu.broadcastChanges();
        } catch (Throwable failure) { done = true; System.out.println("ENDLESS_HIGH_Y_SERVER_FAIL playerWorkflows=" + failure); failure.printStackTrace(); }
    }
    private static Object field(Object object, String name) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (NoSuchFieldException missing) {}
        throw new NoSuchFieldException(name);
    }
    private static net.minecraft.world.level.block.state.BlockState block(String id) { var key = ResourceLocation.tryParse("create:" + id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned Create block: " + id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static ItemStack item(String id) { ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse("create:" + id))); require(!stack.isEmpty(), "missing pinned Create item: " + id); return stack; }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
