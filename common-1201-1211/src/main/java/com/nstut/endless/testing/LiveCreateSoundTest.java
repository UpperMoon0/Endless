package com.nstut.endless.testing;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Native pool callbacks and unmodified boiler sound callbacks reaching Minecraft. */
public final class LiveCreateSoundTest {
    private static final int[] HEIGHTS = {64, -64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private static final List<SoundInstance> submitted = new ArrayList<>();
    private static boolean observing;
    private LiveCreateSoundTest() {}
    public static void observe(SoundInstance sound) { if (observing) submitted.add(sound); }

    public static void run(Minecraft minecraft) throws ReflectiveOperationException {
        Class<?> poolType = Class.forName("com.simibubi.create.content.fluids.tank.SoundPool");
        Class<?> callbackType = Class.forName("com.simibubi.create.content.fluids.tank.SoundPool$Sound");
        List<BlockPos> callbacks = new ArrayList<>();
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getName().equals("playAt")) { Vec3i pos = (Vec3i) args[1]; callbacks.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ())); }
            return null;
        });
        var constructor = poolType.getConstructor(int.class, int.class, callbackType);
        var queue = poolType.getMethod("queueAt", BlockPos.class);
        var packedQueue = poolType.getMethod("queueAt", long.class);
        var play = poolType.getMethod("play", Level.class);
        List<BlockPos> expected = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        Object full = constructor.newInstance(20, 2, callback);
        for (int y : HEIGHTS) { mutable.set(20, y, 20); expected.add(mutable.immutable()); queue.invoke(full, mutable); }
        // Aliased positions and duplicate entries remain separate native sounds.
        expected.add(new BlockPos(20, 1_000_448 + 4096, 20)); queue.invoke(full, expected.get(expected.size()-1));
        expected.add(expected.get(0)); queue.invoke(full, expected.get(0));
        BlockPos legacy = new BlockPos(21, 128, 20); expected.add(legacy); packedQueue.invoke(full, legacy.asLong());
        play.invoke(full, minecraft.level); require(callbacks.isEmpty(), "sound pool ignored merge delay");
        play.invoke(full, minecraft.level); require(callbacks.equals(expected), "native sound callback positions changed: " + callbacks);
        callbacks.clear(); play.invoke(full, minecraft.level); require(callbacks.isEmpty(), "sound pool replayed stale positions");
        queue.invoke(full, new BlockPos(22, -1_000_448, 20)); play.invoke(full, minecraft.level);
        require(callbacks.isEmpty(), "sound pool did not reset merge delay"); play.invoke(full, minecraft.level);
        require(callbacks.equals(List.of(new BlockPos(22, -1_000_448, 20))), "sound pool lost reused position");
        callbacks.clear();
        Object capped = constructor.newInstance(4, 2, callback);
        for (BlockPos pos : expected.subList(0, 10)) queue.invoke(capped, pos);
        play.invoke(capped, minecraft.level); require(callbacks.isEmpty(), "sampled pool ignored merge delay"); play.invoke(capped, minecraft.level);
        require(callbacks.size() == 4 && callbacks.stream().distinct().count() == 4 && expected.containsAll(callbacks), "native sound sampling/cap changed " + callbacks);

        var tankBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:fluid_tank"));
        for (int y : HEIGHTS) {
            BlockPos tankPos = new BlockPos(24, y, 20), enginePos = tankPos.east();
            BlockEntity tank = ((EntityBlock) tankBlock).newBlockEntity(tankPos, tankBlock.defaultBlockState());
            require(tank != null, "missing native boiler tank");
            tank.setLevel(minecraft.level);
            Object boiler = tank.getClass().getField("boiler").get(tank);
            boiler.getClass().getField("attachedEngines").setInt(boiler, 1);
            submitted.clear(); observing = true;
            try {
                boiler.getClass().getMethod("queueSoundOnSide", BlockPos.class, Direction.class).invoke(boiler, enginePos, Direction.EAST);
                var tick = boiler.getClass().getMethod("tick", Class.forName("com.simibubi.create.content.fluids.tank.FluidTankBlockEntity"));
                tick.invoke(boiler, tank); require(submitted.isEmpty(), "boiler ignored merge delay"); tick.invoke(boiler, tank);
                require(submitted.stream().anyMatch(sound -> sound.getLocation().toString().equals("minecraft:block.candle.extinguish")
                    && sound.getX() == enginePos.getX() && sound.getY() == y && sound.getZ() == enginePos.getZ()), "boiler hiss submitted at wrong coordinates y=" + y);
                require(submitted.stream().anyMatch(sound -> sound.getLocation().toString().equals("create:steam")
                    && sound.getX() == enginePos.getX()+.5 && sound.getY() == y+.5 && sound.getZ() == enginePos.getZ()+.5), "native steam sound submitted at wrong coordinates y=" + y + " sounds=" + submitted);
            } finally { observing = false; }
        }
        System.out.println("ENDLESS_CREATE_SOUND_POSITIONS_PASS heights=9 nativeCallbacks=true nativeBoilerSoundManager=true mergeDelay=true samplingCap=true mutableSnapshot=true alias4096=true packedOverload=true queueReuse=true");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
