package com.nstut.endless.mixin;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.ExtendedSectionStorageAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Sparse persistence extension for high-Y POI sections. */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin<R> implements ExtendedSectionStorageAccess {
    @Unique private static final int ENDLESS_POI_FORMAT = 1;

    @Shadow @Final private Long2ObjectMap<Optional<R>> storage;
    @Shadow @Final private Function<Runnable, Codec<R>> codec;
    @Shadow @Final private RegistryAccess registryAccess;
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
    @Shadow protected abstract void onSectionLoad(long sectionKey);

    @Unique private Path endless$poiRoot;
    @Unique private final Set<Long> endless$loadedColumns = new HashSet<>();
    @Unique private final Set<Long> endless$dirtyColumns = new HashSet<>();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void endless$init(
        Path path,
        Function<Runnable, Codec<R>> codec,
        Function<Runnable, R> factory,
        DataFixer fixerUpper,
        DataFixTypes type,
        boolean sync,
        RegistryAccess registryAccess,
        LevelHeightAccessor levelHeightAccessor,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof PoiManager) {
            endless$poiRoot = path.resolve("endless");
        }
    }

    @Inject(method = "getOrLoad", at = @At("HEAD"), cancellable = true)
    private void endless$getOrLoad(long sectionKey, CallbackInfoReturnable<Optional<R>> cir) {
        if (!endless$isExtendedPoiSection(sectionKey)) {
            return;
        }
        long columnKey = ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
        endless$loadColumn(columnKey);
        Optional<R> value = storage.get(sectionKey);
        if (value == null) {
            value = Optional.empty();
            storage.put(sectionKey, value);
        }
        cir.setReturnValue(value);
    }

    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void endless$setDirty(long sectionKey, CallbackInfo ci) {
        if (!endless$isExtendedPoiSection(sectionKey)) {
            return;
        }
        ci.cancel();
        Optional<R> value = storage.get(sectionKey);
        if (value != null && value.isPresent()) {
            endless$dirtyColumns.add(ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey)));
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void endless$tick(BooleanSupplier hasTime, CallbackInfo ci) {
        if (!endless$isPoiStorage()) {
            return;
        }
        while (!endless$dirtyColumns.isEmpty() && hasTime.getAsBoolean()) {
            endless$saveColumn(endless$dirtyColumns.iterator().next());
        }
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void endless$flush(ChunkPos chunkPos, CallbackInfo ci) {
        endless$flushExtendedColumn(chunkPos);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void endless$close(CallbackInfo ci) {
        if (!endless$isPoiStorage()) {
            return;
        }
        for (long columnKey : new ArrayList<>(endless$dirtyColumns)) {
            endless$saveColumn(columnKey);
        }
    }

    @Override
    public List<?> endless$getExtendedSections(ChunkPos chunkPos) {
        if (!endless$isPoiStorage() || !EndlessLogicalHeights.isActive()) {
            return List.of();
        }
        long columnKey = chunkPos.toLong();
        endless$loadColumn(columnKey);
        ArrayList<R> result = new ArrayList<>();
        for (Long2ObjectMap.Entry<Optional<R>> entry : storage.long2ObjectEntrySet()) {
            long sectionKey = entry.getLongKey();
            if (SectionPos.x(sectionKey) == chunkPos.x && SectionPos.z(sectionKey) == chunkPos.z
                && endless$isExtendedPoiSection(sectionKey)) {
                entry.getValue().ifPresent(result::add);
            }
        }
        return result;
    }

    @Override
    public void endless$flushExtendedColumn(ChunkPos chunkPos) {
        if (!endless$isPoiStorage()) {
            return;
        }
        long columnKey = chunkPos.toLong();
        if (endless$dirtyColumns.contains(columnKey)) {
            endless$saveColumn(columnKey);
        }
    }

    @Override
    public void endless$unloadExtendedColumn(ChunkPos chunkPos) {
        if (!endless$isPoiStorage()) {
            return;
        }
        long columnKey = chunkPos.toLong();
        if (endless$dirtyColumns.contains(columnKey)) {
            endless$saveColumn(columnKey);
        }
        Iterator<Long2ObjectMap.Entry<Optional<R>>> iterator = storage.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            long sectionKey = iterator.next().getLongKey();
            if (SectionPos.x(sectionKey) == chunkPos.x && SectionPos.z(sectionKey) == chunkPos.z
                && endless$isExtendedPoiSection(sectionKey)) {
                iterator.remove();
            }
        }
        endless$loadedColumns.remove(columnKey);
    }

    @Unique
    private boolean endless$isPoiStorage() {
        return endless$poiRoot != null && (Object) this instanceof PoiManager;
    }

    @Unique
    private boolean endless$isExtendedPoiSection(long sectionKey) {
        if (!endless$isPoiStorage() || !EndlessLogicalHeights.isActive()) {
            return false;
        }
        int blockY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        return EndlessLogicalHeights.contains(blockY) && EndlessHeights.isOutsideBuildHeight(blockY);
    }

    @Unique
    private void endless$loadColumn(long columnKey) {
        if (endless$loadedColumns.contains(columnKey)) {
            return;
        }
        int chunkX = ChunkPos.getX(columnKey);
        int chunkZ = ChunkPos.getZ(columnKey);
        Path file = endless$file(chunkX, chunkZ);
        if (Files.isRegularFile(file)) {
            try {
                CompoundTag root = NbtIo.readCompressed(file.toFile());
                if (root.getInt("FormatVersion") != ENDLESS_POI_FORMAT
                    || root.getInt("ChunkX") != chunkX || root.getInt("ChunkZ") != chunkZ) {
                    throw new IOException("Invalid Endless POI sidecar header at " + file);
                }
                RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registryAccess);
                CompoundTag sections = root.getCompound("Sections");
                for (String name : sections.getAllKeys()) {
                    final int sectionY;
                    try {
                        sectionY = Integer.parseInt(name);
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid Endless POI section key '" + name + "' at " + file, e);
                    }
                    long sectionKey = SectionPos.asLong(chunkX, sectionY, chunkZ);
                    if (!endless$isExtendedPoiSection(sectionKey)) {
                        throw new IOException("Out-of-range Endless POI section " + sectionY + " at " + file);
                    }
                    DataResult<R> decoded = codec.apply(() -> endless$dirtyColumns.add(columnKey))
                        .parse(ops, sections.get(name));
                    Optional<R> value = decoded.result();
                    if (value.isEmpty()) {
                        throw new IOException("Could not decode Endless POI section " + sectionY + " at " + file);
                    }
                    storage.put(sectionKey, value);
                    onSectionLoad(sectionKey);
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Failed to load Endless POI column " + chunkX + "," + chunkZ, e);
            }
        }
        endless$loadedColumns.add(columnKey);
    }

    @Unique
    private void endless$saveColumn(long columnKey) {
        int chunkX = ChunkPos.getX(columnKey);
        int chunkZ = ChunkPos.getZ(columnKey);
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registryAccess);
            CompoundTag sections = new CompoundTag();
            int written = 0;
            for (Long2ObjectMap.Entry<Optional<R>> entry : storage.long2ObjectEntrySet()) {
                long sectionKey = entry.getLongKey();
                if (SectionPos.x(sectionKey) != chunkX || SectionPos.z(sectionKey) != chunkZ
                    || !endless$isExtendedPoiSection(sectionKey) || entry.getValue().isEmpty()) {
                    continue;
                }
                int sectionY = SectionPos.y(sectionKey);
                DataResult<Tag> encoded = codec.apply(() -> endless$dirtyColumns.add(columnKey))
                    .encodeStart(ops, entry.getValue().get());
                Optional<Tag> tag = encoded.result();
                if (tag.isEmpty()) {
                    throw new IOException("Could not encode Endless POI section " + sectionY);
                }
                sections.put(Integer.toString(sectionY), tag.get());
                written++;
            }

            Path file = endless$file(chunkX, chunkZ);
            if (written == 0) {
                Files.deleteIfExists(file);
                endless$dirtyColumns.remove(columnKey);
                return;
            }

            CompoundTag root = new CompoundTag();
            root.putInt("FormatVersion", ENDLESS_POI_FORMAT);
            root.putInt("ChunkX", chunkX);
            root.putInt("ChunkZ", chunkZ);
            root.put("Sections", sections);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp.toFile());
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            endless$dirtyColumns.remove(columnKey);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to persist Endless POI column " + chunkX + "," + chunkZ, e);
        }
    }

    @Unique
    private Path endless$file(int chunkX, int chunkZ) {
        return endless$poiRoot.resolve("c." + chunkX + "." + chunkZ + ".nbt");
    }
}
