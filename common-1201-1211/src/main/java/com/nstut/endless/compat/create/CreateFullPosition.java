package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exact-position helpers for optional Create compatibility hooks. */
public final class CreateFullPosition {
    public static final String LINK_LAST_POSITION = "EndlessLastKnownPosition";

    private CreateFullPosition() {}

    public static String displayLineKey(int line) {
        return "EndlessLine" + line + "Position";
    }

    public static void put(CompoundTag parent, String key, BlockPos pos) {
        CompoundTag value = new CompoundTag();
        value.putInt("X", pos.getX());
        value.putInt("Y", pos.getY());
        value.putInt("Z", pos.getZ());
        parent.put(key, value);
    }

    public static boolean contains(CompoundTag parent, String key) {
        return parent.contains(key, 10);
    }

    public static BlockPos get(CompoundTag parent, String key) {
        CompoundTag value = parent.getCompound(key);
        return new BlockPos(value.getInt("X"), value.getInt("Y"), value.getInt("Z"));
    }

    public static CompoundTag persistentData(BlockEntity blockEntity) {
        try {
            Method method = blockEntity.getClass().getMethod("getPersistentData");
            Object value = method.invoke(blockEntity);
            if (!(value instanceof CompoundTag tag)) {
                throw new IllegalStateException("Create target persistent data is not a CompoundTag");
            }
            return tag;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Loader BlockEntity#getPersistentData is unavailable", e);
        }
    }
    public static BlockPos blockPosFromDisplayContext(Object context) {
        try {
            Method method = context.getClass().getMethod("blockEntity");
            Object value = method.invoke(context);
            if (!(value instanceof BlockEntity blockEntity)) {
                throw new IllegalStateException("Create DisplayLinkContext#blockEntity did not return a BlockEntity");
            }
            return blockEntity.getBlockPos();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Create DisplayLinkContext shape changed", e);
        }
    }

    public static BlockPos blockPosFromBehaviour(Object behaviour) {
        Class<?> type = behaviour.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("blockEntity");
                field.setAccessible(true);
                Object value = field.get(behaviour);
                if (!(value instanceof BlockEntity blockEntity)) {
                    throw new IllegalStateException("Create behaviour blockEntity field is not a BlockEntity");
                }
                return blockEntity.getBlockPos();
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not read Create behaviour blockEntity", e);
            }
        }
        throw new IllegalStateException("Create behaviour no longer exposes blockEntity in its hierarchy");
    }

    public static boolean isCreateDisplayLink(BlockEntity target, BlockPos pos) {
        if (target.getLevel() == null) {
            return false;
        }
        var id = BuiltInRegistries.BLOCK.getKey(target.getLevel().getBlockState(pos).getBlock());
        return id != null && "create".equals(id.getNamespace()) && "display_link".equals(id.getPath());
    }
}
