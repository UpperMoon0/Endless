package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.ExtendedSectionStorageAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;
import java.util.stream.Stream;

/** Adds sparse high-Y POI sections to normal PoiManager search operations. */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {
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
