package com.nstut.endless.mixin;

import com.mojang.datafixers.DataFixer;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.ExtendedSectionStorageAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Adds sparse high-Y POI sections to normal PoiManager search operations. */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void endless$setSparsePoiRoot(
        RegionStorageInfo info,
        Path folder,
        DataFixer fixerUpper,
        boolean sync,
        RegistryAccess registryAccess,
        ChunkIOErrorReporter errorReporter,
        LevelHeightAccessor levelHeightAccessor,
        CallbackInfo ci
    ) {
        ((ExtendedSectionStorageAccess) (Object) this).endless$setPoiRoot(folder.resolve("endless"));
    }

    @Inject(method = "getInChunk", at = @At("RETURN"), cancellable = true)
    private void endless$getInChunk(
        Predicate<Holder<PoiType>> typePredicate,
        ChunkPos chunkPos,
        PoiManager.Occupancy occupancy,
        CallbackInfoReturnable<Stream<PoiRecord>> cir
    ) {
        if (!EndlessLogicalHeights.isActive()) {
            return;
        }

        ExtendedSectionStorageAccess storage = (ExtendedSectionStorageAccess) (Object) this;
        Stream<PoiRecord> sparse = storage.endless$getExtendedSections(chunkPos).stream()
            .filter(PoiSection.class::isInstance)
            .map(PoiSection.class::cast)
            .flatMap(section -> section.getRecords(typePredicate, occupancy));
        cir.setReturnValue(Stream.concat(cir.getReturnValue(), sparse));
    }
}
