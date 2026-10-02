package com.nstut.endless.testing;

import java.nio.file.Files;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Native cart coupling, world persistence, powered travel and conserved cargo. */
public final class LiveCreateCartRestartTest {
    private static final int[] SEAMS = {64, -64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private static ListTag saved;
    private static int ticks;
    private LiveCreateCartRestartTest() {}
    private static BlockPos rail(int seam) { return new BlockPos(16, seam - 1, 32); }
    private static java.nio.file.Path checkpoint(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("endless-create-cart-checkpoint.snbt"); }
    public static void prepare(ServerLevel level) throws Exception {
        ListTag cases = new ListTag();
        var player = level.getServer().getPlayerList().getPlayers().get(0);
        var mode = player.gameMode.getGameModeForPlayer();
        ItemStack held = player.getMainHandItem().copy();
        try {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            for (int seam : SEAMS) {
                BlockPos root = rail(seam);
                for (int x = 1; x <= 3; x++) level.setChunkForced(x, 2, true);
                for (int x = 0; x <= 40; x++) {
                    level.setBlock(root.east(x).below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(root.east(x), Blocks.POWERED_RAIL.defaultBlockState().setValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE, net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST).setValue(BlockStateProperties.POWERED, false), 3);
                    level.setBlock(root.east(x).above(), Blocks.AIR.defaultBlockState(), 3);
                }
                MinecartChest a = EntityType.CHEST_MINECART.create(level), b = EntityType.CHEST_MINECART.create(level);
                require(a != null && b != null, "native cargo cart creation failed");
                a.setPos(22.5, seam - .9375, 32.5); b.setPos(28.5, seam - .9375, 32.5);
                a.setItem(0, new ItemStack(Items.DIAMOND, 7)); b.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                require(level.addFreshEntity(a) && level.addFreshEntity(b), "native cargo cart world insertion failed");
                Class<?> capabilities = Class.forName("com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController");
                capabilities.getMethod("tick", net.minecraft.world.level.Level.class).invoke(null, level);
                var key = net.minecraft.resources.ResourceLocation.tryParse("create:minecart_coupling");
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
                require(item != Items.AIR, "pinned cart coupling item missing");
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(item));
                var couple = Class.forName("com.simibubi.create.content.contraptions.minecart.CouplingHandler").getMethod("tryToCoupleCarts", net.minecraft.world.entity.player.Player.class, net.minecraft.world.level.Level.class, int.class, int.class);
                require((boolean) couple.invoke(null, player, level, a.getId(), b.getId()), "native cart coupling refused seam=" + seam);
                require(player.getMainHandItem().isEmpty(), "survival coupling did not consume one coupling item");
                require(!(boolean) couple.invoke(null, player, level, a.getId(), b.getId()), "native duplicate coupling negative control accepted");
                linked(level, a, b);
                CompoundTag entry = new CompoundTag(); entry.putInt("Seam", seam); entry.putUUID("A", a.getUUID()); entry.putUUID("B", b.getUUID()); cases.add(entry);
            }
        } finally { player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held); player.setGameMode(mode); }
        CompoundTag root = new CompoundTag(); root.put("Cases", cases); Files.writeString(checkpoint(level), root.toString());
        System.out.println("ENDLESS_CREATE_CART_RESTART_PREPARED cases=9 nativeCoupling=true survivalCost=true duplicateControl=true");
    }
    public static boolean verify(ServerLevel level) throws Exception {
        if (saved == null) {
            saved = TagParser.parseTag(Files.readString(checkpoint(level))).getList("Cases", 10);
            require(saved.size() == SEAMS.length, "cart checkpoint missing cases");
            for (int i = 0; i < saved.size(); i++) {
                var entry = saved.getCompound(i); int seam = entry.getInt("Seam"); require(seam == SEAMS[i], "cart checkpoint seam mismatch");
                MinecartChest a = cart(level, entry, "A"), b = cart(level, entry, "B"); linked(level, a, b); cargo(a, b);
                require(Math.abs(a.getY() - (seam - .9375)) < .2 && Math.abs(b.getY() - a.getY()) < .01, "world-loaded carts changed full height");
                entry.putDouble("StartA", a.getX()); entry.putDouble("StartB", b.getX());
                for (int x = 0; x <= 40; x++) level.setBlock(rail(seam).east(x).below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                a.setDeltaMovement(new Vec3(.2, 0, 0)); b.setDeltaMovement(new Vec3(.2, 0, 0));
            }
        }
        if (++ticks < 40) return false;
        for (int i = 0; i < saved.size(); i++) {
            var entry = saved.getCompound(i); MinecartChest a = cart(level, entry, "A"), b = cart(level, entry, "B");
            linked(level, a, b); cargo(a, b);
            require(a.getX() - entry.getDouble("StartA") > 2 && b.getX() - entry.getDouble("StartB") > 2, "coupled carts did not move on native powered-rail ticks seam=" + entry.getInt("Seam") + " startA=" + entry.getDouble("StartA") + " startB=" + entry.getDouble("StartB") + " a=" + a.position() + " b=" + b.position() + " velocityA=" + a.getDeltaMovement() + " velocityB=" + b.getDeltaMovement() + " ticksA=" + a.tickCount + " ticksB=" + b.tickCount + " railA=" + level.getBlockState(a.blockPosition()) + " railB=" + level.getBlockState(b.blockPosition()));
            require(a.position().distanceTo(b.position()) > 2 && a.position().distanceTo(b.position()) < 9, "native coupling failed to retain cart spacing");
            require(Math.abs(a.getY() - (entry.getInt("Seam") - .9375)) < .2 && Math.abs(b.getY()-a.getY()) < .01, "coupled moving carts aliased height");
        }
        System.out.println("ENDLESS_CREATE_CART_RESTART_PASS cases=9 freshJvm=true nativeCoupling=true poweredRailTravel=true exactCargo=true");
        return true;
    }
    private static MinecartChest cart(ServerLevel level, CompoundTag entry, String name) {
        var entity = level.getEntity(entry.getUUID(name)); require(entity instanceof MinecartChest && entity.isAlive(), "world-loaded cargo cart missing " + name); return (MinecartChest) entity;
    }
    private static void cargo(MinecartChest a, MinecartChest b) {
        require(a.getItem(0).is(Items.DIAMOND) && a.getItem(0).getCount() == 7 && b.getItem(0).is(Items.IRON_INGOT) && b.getItem(0).getCount() == 3, "coupled carts lost or duplicated cargo");
        for (int i = 1; i < a.getContainerSize(); i++) require(a.getItem(i).isEmpty() && b.getItem(i).isEmpty(), "coupled cart cargo duplicated into another slot");
    }
    private static void linked(ServerLevel level, MinecartChest a, MinecartChest b) throws Exception {
        var type = Class.forName("com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController");
        var get = type.getMethod("getIfPresent", net.minecraft.world.level.Level.class, java.util.UUID.class);
        Object ca = get.invoke(null, level, a.getUUID()), cb = get.invoke(null, level, b.getUUID());
        require(ca != null && cb != null, "native cart controller missing after world load");
        require(b.getUUID().equals(ca.getClass().getMethod("getCoupledCart", boolean.class).invoke(ca, true)) && a.getUUID().equals(cb.getClass().getMethod("getCoupledCart", boolean.class).invoke(cb, false)), "native reciprocal cart coupling changed across save");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
