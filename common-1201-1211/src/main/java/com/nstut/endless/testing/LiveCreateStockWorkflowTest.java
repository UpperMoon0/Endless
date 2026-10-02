package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Native linked stock requests must conserve stock through packaging and unpacking. */
public final class LiveCreateStockWorkflowTest {
    private LiveCreateStockWorkflowTest() {}
    public static BlockPos ticker(BlockPos base) { return base.offset(3, 0, 4); }
    private static BlockPos source(BlockPos base) { return base.east(6); }
    private static BlockPos output(BlockPos base) { return base.offset(6, 0, 4); }
    private static BlockPos requester(BlockPos base) { return base.offset(8, 0, 4); }
    public static Object order() throws Exception {
        Object stack = Class.forName("com.simibubi.create.content.logistics.BigItemStack").getConstructor(ItemStack.class, int.class).newInstance(new ItemStack(Items.DIAMOND), 7);
        return Class.forName("com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts").getMethod("simple", List.class).invoke(null, List.of(stack));
    }
    public static void prepare(ServerLevel level, ServerPlayer player, BlockPos base) throws Exception {
        for (BlockPos p : List.of(source(base), output(base))) {
            level.setBlock(p.below(), Blocks.CHEST.defaultBlockState(), 18);
            level.setBlock(p, block("packager").setValue(BlockStateProperties.FACING, Direction.UP), 3);
        }
        ((ChestBlockEntity) level.getBlockEntity(source(base).below())).setItem(0, new ItemStack(Items.DIAMOND, 14));
        BlockPos link = source(base).above();
        level.setBlock(link, block("stock_link").setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        level.setBlock(ticker(base), block("stock_ticker"), 3); level.setBlock(requester(base), block("redstone_requester"), 3);
        UUID network = UUID.randomUUID();
        Object linkEntity = level.getBlockEntity(link);
        fieldObject(linkEntity, "placedBy").set(linkEntity, player.getUUID());
        for (BlockPos p : List.of(link, ticker(base), requester(base))) {
            Object behaviour = field(level.getBlockEntity(p), "behaviour");
            fieldObject(behaviour, "freqId").set(behaviour, network);
        }
        Object request = level.getBlockEntity(requester(base));
        fieldObject(request, "encodedRequest").set(request, order());
        fieldObject(request, "encodedTargetAdress").set(request, "Endless-player-" + base.getY());
    }
    public static void assertStock(ServerLevel level, BlockPos base, int expected) throws Exception {
        Object ticker = level.getBlockEntity(ticker(base)), summary = ticker.getClass().getMethod("getAccurateSummary").invoke(ticker);
        int available = ((Number) summary.getClass().getMethod("getCountOf", ItemStack.class).invoke(summary, new ItemStack(Items.DIAMOND))).intValue();
        require(available == expected, "native stock-link summary mismatch expected=" + expected + " actual=" + available);
    }
    public static boolean unpack(ServerLevel level, BlockPos base, int expectedTotal) throws Exception {
        Object packager = level.getBlockEntity(source(base)), inventory = field(packager, "inventory");
        ItemStack box = (ItemStack) inventory.getClass().getMethod("extractItem", int.class, int.class, boolean.class).invoke(inventory, 0, 1, true);
        if (box.isEmpty()) return false;
        Class<?> type = Class.forName("com.simibubi.create.content.logistics.box.PackageItem");
        require(box.getCount() == 1 && (boolean) type.getMethod("isPackage", ItemStack.class).invoke(null, box), "stock request emitted invalid package");
        require(("Endless-player-" + base.getY()).equals(type.getMethod("getAddress", ItemStack.class).invoke(null, box)), "stock request packet address changed");
        Object contents = type.getMethod("getContents", ItemStack.class).invoke(null, box); int diamonds = 0;
        for (int i = 0; i < 9; i++) { ItemStack item = (ItemStack) contents.getClass().getMethod("getStackInSlot", int.class).invoke(contents, i); require(item.isEmpty() || item.is(Items.DIAMOND), "stock package payload identity changed"); diamonds += item.getCount(); }
        require(diamonds == 7, "stock packet lost or duplicated ordered contents");
        box = (ItemStack) inventory.getClass().getMethod("extractItem", int.class, int.class, boolean.class).invoke(inventory, 0, 1, false);
        Object target = field(level.getBlockEntity(output(base)), "inventory");
        ItemStack leftover = (ItemStack) target.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class).invoke(target, 0, box, false);
        require(leftover.isEmpty(), "native stock-order unpacking failed");
        require(count((ChestBlockEntity) level.getBlockEntity(output(base).below())) == expectedTotal
            && count((ChestBlockEntity) level.getBlockEntity(source(base).below())) == 14 - expectedTotal, "stock request/unpacking failed exact conservation");
        require(((ItemStack) field(packager, "heldBox")).isEmpty(), "stock request left a duplicate held package");
        return true;
    }
    public static void trigger(ServerLevel level, BlockPos base) {
        level.setBlock(requester(base).east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
    }
    public static void assertRequester(ServerLevel level, BlockPos base) throws Exception {
        require((boolean) field(level.getBlockEntity(requester(base)), "lastRequestSucceeded"), "native redstone requester did not execute its linked order");
        assertStock(level, base, 0);
    }
    private static int count(ChestBlockEntity chest) {
        int total = 0; for (int i = 0; i < chest.getContainerSize(); i++) { ItemStack item = chest.getItem(i); require(item.isEmpty() || item.is(Items.DIAMOND), "unexpected stock inventory item"); total += item.getCount(); } return total;
    }
    private static Object field(Object object, String name) throws Exception { return fieldObject(object, name).get(object); }
    private static Field fieldObject(Object object, String name) throws Exception { for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {} throw new NoSuchFieldException(name); }
    private static BlockState block(String id) { var key = ResourceLocation.tryParse("create:" + id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned block " + id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
