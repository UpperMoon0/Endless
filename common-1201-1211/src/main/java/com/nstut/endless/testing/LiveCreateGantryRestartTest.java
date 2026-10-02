package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;

/** A world-saved translating drill must resume work and retain its mounted inventory. */
public final class LiveCreateGantryRestartTest {
    private static final int[] SEAMS = {64, -64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private static ListTag restored;
    private static int ticks;
    private LiveCreateGantryRestartTest() {}
    private static BlockPos root(int seam) { return new BlockPos(130, seam - 2, 70); }
    private static java.nio.file.Path checkpoint(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("endless-create-gantry-checkpoint.snbt");
    }
    public static void prepare(ServerLevel level) throws Exception {
        ListTag cases = new ListTag();
        for (int seam : SEAMS) {
            BlockPos root = root(seam), carriage = root.east(), payload = root.east(2);
            for (BlockPos p : BlockPos.betweenClosed(root.offset(-1, -1, -1), root.offset(3, 63, 2)))
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
            for (int i = 0; i < 64; i++)
                level.setBlock(root.above(i), block("gantry_shaft").setValue(BlockStateProperties.FACING, Direction.UP), 3);
            BlockState pinion = block("gantry_carriage").setValue(BlockStateProperties.FACING, Direction.EAST);
            Class<?> pinionType = Class.forName("com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock");
            if (pinionType.getMethod("getValidGantryShaftAxis", BlockState.class).invoke(null, pinion) != Direction.Axis.Y)
                pinion = pinion.cycle((net.minecraft.world.level.block.state.properties.BooleanProperty) pinionType.getField("AXIS_ALONG_FIRST_COORDINATE").get(null));
            require(pinionType.getMethod("getValidGantryShaftAxis", BlockState.class).invoke(null, pinion) == Direction.Axis.Y,
                "native gantry carriage orientation invalid");
            level.setBlock(carriage, pinion, 3);
            level.setBlock(payload, Blocks.SLIME_BLOCK.defaultBlockState(), 18);
            level.setBlock(payload.above(), block("mechanical_drill").setValue(BlockStateProperties.FACING, Direction.UP), 18);
            level.setBlock(payload.south(), Blocks.CHEST.defaultBlockState(), 18);
            ((ChestBlockEntity) level.getBlockEntity(payload.south())).setItem(0, new ItemStack(Items.DIAMOND, 7));
            // Leave the first move unobstructed; native actors then mine these blocks across the seam.
            for (int i = 4; i <= 6; i++) level.setBlock(payload.above(i), Blocks.STONE.defaultBlockState(), 18);
            level.setBlock(root.below(), block("creative_motor").setValue(BlockStateProperties.FACING, Direction.UP), 3);
            Object motor = level.getBlockEntity(root.below());
            Object speed = field(motor, "generatedSpeed"); speed.getClass().getMethod("setValue", int.class).invoke(speed, -128);
            for (int i = 0; i < 10; i++) {
                call(motor, "tick");
                for (int shaft = 0; shaft < 64; shaft++) call(level.getBlockEntity(root.above(shaft)), "tick");
            }
            require(((Number) call(level.getBlockEntity(root), "getPinionMovementSpeed")).floatValue() > 0,
                "native gantry shaft did not drive upward");
            Object controller = level.getBlockEntity(carriage);
            call(controller, "queueAssembly"); call(controller, "tick");
            Entity entity = null;
            for (Entity candidate : level.getAllEntities())
                if (candidate.getClass().getSimpleName().equals("GantryContraptionEntity")
                    && candidate.position().distanceTo(net.minecraft.world.phys.Vec3.atLowerCornerOf(carriage)) < .01) entity = candidate;
            require(entity != null && entity.isAlive(), "native gantry assembly failed seam=" + seam);
            // Native sequenced movement with zero distance parks the assembly during
            // server/client startup without removing its powered shaft or controller.
            entity.getClass().getMethod("limitMovement", double.class).invoke(entity, 0d);
            Object contraption = call(entity, "getContraption");
            require(((List<?>) call(contraption, "getActors")).size() == 1, "native gantry did not capture the drill actor");
            require(count(contraption, Items.DIAMOND) == 7, "mounted chest was not captured exactly");
            CompoundTag entry = new CompoundTag(); entry.putInt("Seam", seam); entry.putUUID("Entity", entity.getUUID());
            entry.put("Contraption", LiveCreateNbt.writeContraption(level, contraption)); cases.add(entry);
        }
        CompoundTag root = new CompoundTag(); root.put("Cases", cases); Files.writeString(checkpoint(level), root.toString());
        System.out.println("ENDLESS_CREATE_GANTRY_RESTART_PREPARED cases=9 nativeDrill=true mountedInventory=true");
    }
    public static boolean verify(ServerLevel level) throws Exception {
        if (restored == null) {
            restored = TagParser.parseTag(Files.readString(checkpoint(level))).getList("Cases", 10);
            require(restored.size() == SEAMS.length, "gantry checkpoint missing cases");
            for (int i = 0; i < restored.size(); i++) {
                CompoundTag entry = restored.getCompound(i); require(entry.getInt("Seam") == SEAMS[i], "gantry seam checkpoint mismatch");
                Entity entity = level.getEntity(entry.getUUID("Entity")); require(entity != null && entity.isAlive(), "world-loaded gantry missing");
                CompoundTag loaded = LiveCreateNbt.writeContraption(level, call(entity, "getContraption"));
                for (String key : List.of("Blocks", "Anchor", "Facing")) require(java.util.Objects.equals(entry.getCompound("Contraption").get(key), loaded.get(key)), "restored gantry payload changed " + key);
                require(entity.position().distanceTo(net.minecraft.world.phys.Vec3.atLowerCornerOf(root(entry.getInt("Seam")).east())) < .01,
                    "saved sequenced gantry moved before phase-B resume");
                entity.getClass().getMethod("limitMovement", double.class).invoke(entity, 48d);
            }
        }
        require(++ticks < 500, "restored gantry drill failed to finish work");
        for (int i = 0; i < restored.size(); i++) {
            CompoundTag entry = restored.getCompound(i); BlockPos root = root(entry.getInt("Seam"));
            Entity entity = level.getEntity(entry.getUUID("Entity")); require(entity != null && entity.isAlive(), "gantry lost its restored moving entity");
            Object contraption = call(entity, "getContraption");
            require(count(contraption, Items.DIAMOND) == 7, "moving drill lost or duplicated mounted inventory");
            for (int y = 4; y <= 6; y++) if (!level.getBlockState(root.east(2).above(y)).isAir()) return false;
            require(count(contraption, Items.COBBLESTONE) == 3, "native moving drill did not collect exactly three mined drops");
            require(entity.getY() > root.getY() + 4 && entity.getY() < root.getY() + 60, "gantry translated to an incorrect full-height position");
        }
        // Native disassembly must restore the same exact inventory at the translated block position.
        for (int i = 0; i < restored.size(); i++) {
            CompoundTag entry = restored.getCompound(i); Entity entity = level.getEntity(entry.getUUID("Entity"));
            var anchor = (net.minecraft.world.phys.Vec3) call(entity, "getAnchorVec");
            BlockPos carriage = BlockPos.containing(anchor.add(.5, .5, .5)); call(entity, "disassemble");
            require(level.getBlockState(carriage).getBlock() == block("gantry_carriage").getBlock(), "gantry carriage did not restore at its translated anchor");
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(carriage.east().south());
            int diamonds = 0, cobble = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) { ItemStack item = chest.getItem(slot); require(item.isEmpty() || item.is(Items.DIAMOND) || item.is(Items.COBBLESTONE), "unexpected restored mining inventory"); if (item.is(Items.DIAMOND)) diamonds += item.getCount(); if (item.is(Items.COBBLESTONE)) cobble += item.getCount(); }
            require(diamonds == 7 && cobble == 3, "native disassembly changed mining inventory");
        }
        System.out.println("ENDLESS_CREATE_GANTRY_RESTART_PASS cases=9 freshJvm=true naturalWorldTicks=true nativeDrill=true stoneMined=3 exactMountedInventory=true nativeDisassembly=true");
        return true;
    }
    private static int count(Object contraption, net.minecraft.world.item.Item expected) throws Exception {
        Object inventory = call(call(contraption, "getStorage"), "getAllItems"); int total = 0;
        int slots = ((Number) call(inventory, "getSlots")).intValue();
        for (int i = 0; i < slots; i++) { ItemStack item = (ItemStack) inventory.getClass().getMethod("getStackInSlot", int.class).invoke(inventory, i); if (item.is(expected)) total += item.getCount(); }
        return total;
    }
    private static Object call(Object object, String name) throws Exception { return object.getClass().getMethod(name).invoke(object); }
    private static Object field(Object object, String name) throws Exception { for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (NoSuchFieldException ignored) {} throw new NoSuchFieldException(name); }
    private static BlockState block(String id) { var key = ResourceLocation.tryParse("create:" + id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned block " + id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
