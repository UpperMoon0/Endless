package com.nstut.endless.compat.create;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CreateKineticStorageTest {
    @TempDir Path scratch;
    @Test void realSavedDataBoundaryNeverResetsCorruptAllocator() throws Exception {
        CreateKineticStorageProbe.run(scratch);
    }
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC}) // Windows requires an optional symlink privilege.
    void danglingAllocatorLinkIsNotAnAbsentNewWorldFile() throws Exception {
        CreateKineticStorageProbe.verifyDanglingAllocatorRefused(scratch);
    }

}
