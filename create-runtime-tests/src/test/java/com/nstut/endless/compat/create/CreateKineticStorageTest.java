package com.nstut.endless.compat.create;

import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CreateKineticStorageTest {
    @TempDir Path scratch;
    @Test void realSavedDataBoundaryNeverResetsCorruptAllocator() throws Exception {
        CreateKineticStorageProbe.run(scratch);
    }
    @Test void versionThreeRetainsBothRolesAcrossSaveAndReload() {
        var data = new CreateKineticIdData();
        var pos = new BlockPos(10, 1_000_000, 10);
        long follower = data.idForFollowerPosition(pos);
        long generator = data.idForPosition(pos);
        CompoundTag saved = CreateKineticStorageProbe.snapshot(data);
        assertEquals(3, saved.getInt("Namespace"));
        var restored = CreateKineticIdData.load(saved);
        assertEquals(follower, restored.idForFollowerPosition(pos));
        assertEquals(generator, restored.idForPosition(pos));
        assertNotEquals(generator, follower);
        assertNotEquals(generator, restored.idForPosition(pos.above()));
        assertNotEquals(follower, restored.idForFollowerPosition(pos.above()));
    }

    @Test void versionTwoSparseFollowerCannotBeReinterpretedAsGenerator() {
        var draft = new CreateKineticIdData();
        draft.idForFollowerPosition(new BlockPos(10, 1_000_000, 10));
        CompoundTag saved = CreateKineticStorageProbe.snapshot(draft);
        saved.putInt("Namespace", 2);
        assertThrows(IllegalArgumentException.class, () -> CreateKineticIdData.load(saved));
        saved.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).remove("Follower");
        assertThrows(IllegalArgumentException.class, () -> CreateKineticIdData.load(saved));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC}) // Windows requires an optional symlink privilege.
    void danglingAllocatorLinkIsNotAnAbsentNewWorldFile() throws Exception {
        CreateKineticStorageProbe.verifyDanglingAllocatorRefused(scratch);
    }

}
