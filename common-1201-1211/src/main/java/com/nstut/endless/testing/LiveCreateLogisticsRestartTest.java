package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Native frogport animation, addressed chain routing and delivery across a fresh server JVM. */
public final class LiveCreateLogisticsRestartTest {
    private static final int[] SEAMS = {64, -64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private static int ticks;
    private LiveCreateLogisticsRestartTest() {}
    private static BlockPos first(int seam) { return new BlockPos(98, seam - 1, 70); }
    private static BlockPos second(int seam) { return first(seam).offset(8, 2, 0); }
    private static BlockPos sender(int seam) { return first(seam).south(3).above(); }
    private static BlockPos receiver(int seam) { return second(seam).south(3).above(); }
    private static BlockPos decoy(int seam) { return second(seam).north(3).above(); }
    public static void prepare(ServerLevel level) throws Exception {
        level.setChunkForced(6, 4, true);
        Class<?> packageItem = Class.forName("com.simibubi.create.content.logistics.box.PackageItem");
        for (int seam : SEAMS) {
            BlockPos a = first(seam), b = second(seam);
            for (BlockPos p : BlockPos.betweenClosed(a.offset(-2, -2, -4), b.offset(2, 4, 4))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
            for (BlockPos p : List.of(a, b)) level.setBlock(p, block("chain_conveyor"), 3);
            level.setBlock(a.below(), block("creative_motor").setValue(BlockStateProperties.FACING, Direction.UP), 3);
            Object motor = level.getBlockEntity(a.below());
            Object speed = field(motor, "generatedSpeed"); speed.getClass().getMethod("setValue", int.class).invoke(speed, 128);
            Object ca = level.getBlockEntity(a), cb = level.getBlockEntity(b);
            // Player connection happens after native initialization; populate the same
            // lazily prepared geometry before invoking that connection operation.
            call(ca, "prepareStats"); call(cb, "prepareStats");
            ca.getClass().getMethod("addConnectionTo", BlockPos.class).invoke(ca, b);
            cb.getClass().getMethod("addConnectionTo", BlockPos.class).invoke(cb, a);
            for (int tick = 0; tick < 10; tick++) for (Object machine : List.of(motor, ca, cb)) call(machine, "tick");
            require(((Number) call(ca, "getSpeed")).floatValue() != 0 && ((Number) call(cb, "getSpeed")).floatValue() != 0, "native chain failed to transmit rotation seam=" + seam);
            port(level, sender(seam), a, false, "", 0);
            port(level, receiver(seam), b, true, "Endless-" + seam, 90);
            port(level, decoy(seam), b, true, "Wrong-address-" + seam, 180);
            ItemStack box = (ItemStack) packageItem.getMethod("containing", List.class).invoke(null, List.of(new ItemStack(Items.DIAMOND, 7)));
            packageItem.getMethod("addAddress", ItemStack.class, String.class).invoke(null, box, "Endless-" + seam);
            Object source = level.getBlockEntity(sender(seam)), inv = field(source, "inventory");
            inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(inv, 0, box);
            call(source, "tryPullingFromOwnAndAdjacentInventories");
            require((boolean) call(source, "isAnimationInProgress") && ((ItemStack) field(source, "animatedPackage")).getCount() == 1, "native frogport did not start exporting the package");
        }
        System.out.println("ENDLESS_CREATE_LOGISTICS_RESTART_PREPARED cases=9 activeFrogportAnimation=true chainCrossesSeams=true addressDecoy=true");
    }
    public static boolean verify(ServerLevel level) throws Exception {
        require(++ticks < 400, "restored addressed chain delivery timed out");
        Class<?> packageItem = Class.forName("com.simibubi.create.content.logistics.box.PackageItem");
        for (int seam : SEAMS) {
            ChestBlockEntity output = (ChestBlockEntity) level.getBlockEntity(receiver(seam).below());
            int boxes = 0, diamonds = 0;
            for (int slot = 0; slot < output.getContainerSize(); slot++) {
                ItemStack box = output.getItem(slot); if (box.isEmpty()) continue;
                require((boolean) packageItem.getMethod("isPackage", ItemStack.class).invoke(null, box), "frogport delivered a non-package");
                require(("Endless-" + seam).equals(packageItem.getMethod("getAddress", ItemStack.class).invoke(null, box)), "address or full-height route changed after restart");
                boxes += box.getCount(); Object contents = packageItem.getMethod("getContents", ItemStack.class).invoke(null, box);
                for (int i = 0; i < 9; i++) {
                    ItemStack item = (ItemStack) contents.getClass().getMethod("getStackInSlot", int.class).invoke(contents, i);
                    require(item.isEmpty() || item.is(Items.DIAMOND), "package payload identity changed"); diamonds += item.getCount();
                }
            }
            require(((ChestBlockEntity) level.getBlockEntity(decoy(seam).below())).isEmpty(), "addressed package reached the wrong receiver");
            if (boxes == 0) return false;
            require(boxes == 1 && diamonds == 7, "native chain duplicated or lost package contents");
            for (BlockPos p : List.of(first(seam), second(seam))) {
                Object conveyor = level.getBlockEntity(p);
                require(((List<?>) field(conveyor, "loopingPackages")).isEmpty() && ((Map<?, ?>) field(conveyor, "travellingPackages")).values().stream().allMatch(v -> ((List<?>) v).isEmpty()), "delivered package remains on chain");
            }
            for (BlockPos p : List.of(sender(seam), receiver(seam), decoy(seam))) {
                Object port = level.getBlockEntity(p), inv = field(port, "inventory");
                if ((boolean) call(port, "isAnimationInProgress")) return false;
                for (int i = 0; i < 18; i++) require(((ItemStack) inv.getClass().getMethod("getStackInSlot", int.class).invoke(inv, i)).isEmpty(), "delivered package duplicated in frogport inventory");
            }
        }
        System.out.println("ENDLESS_CREATE_LOGISTICS_RESTART_PASS cases=9 freshJvm=true nativeAnimations=true naturalTicks=true addressedChainDelivery=true wrongAddressRejected=true exactDiamonds=7");
        return true;
    }
    private static void port(ServerLevel level, BlockPos pos, BlockPos conveyor, boolean accepts, String filter, float angle) throws Exception {
        level.setBlock(pos.below(), Blocks.CHEST.defaultBlockState(), 18); level.setBlock(pos, block("package_frogport"), 3);
        Object port = level.getBlockEntity(pos); call(port, "tick");
        fieldObject(port, "acceptsPackages").setBoolean(port, accepts); fieldObject(port, "addressFilter").set(port, filter);
        Class<?> targetType = Class.forName("com.simibubi.create.content.logistics.packagePort.PackagePortTarget$ChainConveyorFrogportTarget");
        Object target;
        try { target = targetType.getConstructor(BlockPos.class, float.class, BlockPos.class, boolean.class).newInstance(conveyor.subtract(pos), angle, null, false); }
        catch (NoSuchMethodException legacy) { target = targetType.getConstructor(BlockPos.class, float.class, BlockPos.class).newInstance(conveyor.subtract(pos), angle, null); }
        fieldObject(port, "target").set(port, target);
        Class<?> portType = Class.forName("com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity");
        targetType.getMethod("setup", portType, net.minecraft.world.level.LevelAccessor.class, BlockPos.class).invoke(target, port, level, pos);
        targetType.getMethod("register", portType, net.minecraft.world.level.LevelAccessor.class, BlockPos.class).invoke(target, port, level, pos);
    }
    private static Object call(Object object, String name) throws Exception { return object.getClass().getMethod(name).invoke(object); }
    private static Object field(Object object, String name) throws Exception { return fieldObject(object, name).get(object); }
    private static Field fieldObject(Object object, String name) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) {}
        throw new NoSuchFieldException(name);
    }
    private static BlockState block(String id) { var key = ResourceLocation.tryParse("create:" + id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned block " + id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
