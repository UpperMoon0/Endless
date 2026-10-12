package com.nstut.endless.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.nstut.endless.compat.create.CreateDestructionPositions;
import com.nstut.endless.heights.EndlessLogicalHeights;
import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DestructionRenderHookTest {
    @Test void expressionHookHasNoWideSpillAndRetainsExactKeysAndVanillaFallback() throws Exception {
        Method hook = DestructionProgressMixin.class.getDeclaredMethod("endless$renderExactCrack", BlockPos.class, Long2ObjectMap.Entry.class);
        assertNotNull(hook.getAnnotation(ModifyExpressionValue.class));
        assertNotNull(hook.getParameters()[1].getAnnotation(Local.class));
        hook.setAccessible(true);
        DestructionProgressMixin renderer = new DestructionProgressMixin() {};
        var field = DestructionProgressMixin.class.getDeclaredField("endless$crackPositions");
        field.setAccessible(true);
        CreateDestructionPositions positions = (CreateDestructionPositions) field.get(renderer);
        boolean active = EndlessLogicalHeights.isActive();
        try {
            EndlessLogicalHeights.activate();
            for (int y : new int[]{-8_000_000, -1_000_000, 0, 1_000_000, 7_999_999}) {
                BlockPos exact = new BlockPos(7, y, 9);
                long key = positions.key(exact);
                BlockPos packed = BlockPos.of(key);
                var entry = new AbstractLong2ObjectMap.BasicEntry<>(key, null);
                assertEquals(exact, hook.invoke(renderer, packed, entry));
                positions.remove(exact);
                assertEquals(packed, hook.invoke(renderer, packed, entry));
            }
            EndlessLogicalHeights.deactivate();
            BlockPos original = new BlockPos(1, 64, 2);
            assertSame(original, hook.invoke(renderer, original, null));
        } finally {
            if (active) EndlessLogicalHeights.activate(); else EndlessLogicalHeights.deactivate();
        }
    }
}
