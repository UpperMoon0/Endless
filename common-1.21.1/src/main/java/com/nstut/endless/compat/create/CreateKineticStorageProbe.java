package com.nstut.endless.compat.create;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** Disk-backed regression invoked only by opt-in live tests and unit tests. */
public final class CreateKineticStorageProbe {
    private CreateKineticStorageProbe() {}

    public static void run(Path scratch) throws IOException {
        SharedConstants.tryDetectVersion();
        Files.createDirectories(scratch);
        var seed = new CreateKineticIdData();
        var original = new BlockPos(10, 1_000_000, 10);
        var unrelated = new BlockPos(50, 2_000_000, 50);
        long originalId = seed.idForPosition(original);
        var denseFollower = new BlockPos(10, 0, 10);
        long followerId = seed.idForFollowerPosition(denseFollower);
        long samePositionFollower = seed.idForFollowerPosition(original);
        require(samePositionFollower != originalId, "provisional identity reused a former generator ID");
        CompoundTag valid = snapshot(seed);

        Path newFolder = Files.createTempDirectory(scratch, "new-");
        var fresh = CreateKineticIdData.getOrCreate(storage(newFolder), file(newFolder));
        require(fresh.idForPosition(original) == originalId, "genuinely new allocator was rejected");
        Path validFolder = Files.createTempDirectory(scratch, "valid-");
        write(file(validFolder), valid);
        var loaded = CreateKineticIdData.getOrCreate(storage(validFolder), file(validFolder));
        require(loaded.idForPosition(original) == originalId, "valid allocator lost identity");
        require(loaded.idForFollowerPosition(denseFollower) == followerId, "dense provisional follower lost persisted identity");
        require(loaded.idForFollowerPosition(original) == samePositionFollower, "allocator lost same-position role separation");
        require(followerId != originalId, "dense provisional identity collided with sparse root");
        require(loaded.idForPosition(unrelated) != originalId, "valid allocator reused an ID");

        for (String mode : new String[]{"duplicate", "missing-fields", "wrong-list-type", "old-namespace", "v2-unmarked-follower", "v2-marked-follower", "truncated"}) {
            Path folder = Files.createTempDirectory(scratch, mode + "-");
            CompoundTag invalid = valid.copy();
            if (mode.equals("old-namespace")) {
                invalid.remove("Namespace");
                invalid.getList("Entries", 10).getCompound(0).putLong("Id", 2032L);
            } else if (mode.startsWith("v2-")) {
                // Exact ambiguous draft shape: sparse provisional ID, Namespace=2,
                // and no Follower field. Even later marked v2 files must be refused.
                var draft = new CreateKineticIdData();
                draft.idForFollowerPosition(original);
                invalid = snapshot(draft);
                invalid.putInt("Namespace", 2);
                if (mode.equals("v2-unmarked-follower")) {
                    invalid.getList("Entries", 10).getCompound(0).remove("Follower");
                }
            } else if (mode.equals("duplicate")) {
                CompoundTag duplicate = invalid.getList("Entries", 10).getCompound(0).copy();
                duplicate.putInt("X", 11);
                invalid.getList("Entries", 10).add(duplicate);
            } else if (mode.equals("missing-fields")) {
                invalid.remove("NextSequence");
            } else if (mode.equals("wrong-list-type")) {
                ListTag list = new ListTag();
                list.add(IntTag.valueOf(1));
                invalid.put("Entries", list);
            }
            Path dataFile = file(folder);
            if (mode.equals("truncated")) Files.write(dataFile, new byte[]{31, (byte)139, 8});
            else write(dataFile, invalid);
            byte[] originalBytes = Files.readAllBytes(dataFile);
            DimensionDataStorage storage = storage(folder);
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean refused = false;
                try { CreateKineticIdData.getOrCreate(storage, dataFile).idForPosition(unrelated); }
                catch (IllegalStateException expected) {
                    refused = expected.getMessage().contains("Refusing to reset");
                }
                require(refused, mode + " allocator silently reset through SavedData boundary");
            }
            storage.save();
            require(Arrays.equals(originalBytes, Files.readAllBytes(dataFile)), "corrupt allocator was overwritten");
        }
        System.out.println("ENDLESS_CREATE_ALLOCATOR_FAIL_CLOSED_PASS diskBacked=true cases=7 namespaceV2Refused=true");
    }

    /** A filesystem entry still exists when a symlink's target has disappeared. */
    public static void verifyDanglingAllocatorRefused(Path folder) throws IOException {
        SharedConstants.tryDetectVersion();
        Files.createDirectories(folder);
        Path dataFile = file(folder);
        Files.createSymbolicLink(dataFile, Path.of("missing-allocator-target.dat"));
        DimensionDataStorage storage = storage(folder);
        boolean refused = false;
        try {
            CreateKineticIdData.getOrCreate(storage, dataFile).idForPosition(new BlockPos(10, 1_000_000, 10));
        } catch (IllegalStateException expected) {
            refused = expected.getMessage().contains("Refusing to reset");
        }
        require(refused, "dangling allocator symlink silently started a new namespace");
        storage.save();
        require(Files.isSymbolicLink(dataFile), "dangling allocator link was overwritten");
    }

    static CompoundTag snapshot(CreateKineticIdData data) { return data.save(new CompoundTag(), null); }

    private static Path file(Path folder) { return folder.resolve(CreateKineticIdData.DATA_NAME + ".dat"); }
    private static DimensionDataStorage storage(Path folder) { return new DimensionDataStorage(folder.toFile(), null, null); }
    private static void write(Path file, CompoundTag data) throws IOException {
        CompoundTag disk = new CompoundTag();
        disk.put("data", data);
        disk.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        NbtIo.writeCompressed(disk, file);
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
