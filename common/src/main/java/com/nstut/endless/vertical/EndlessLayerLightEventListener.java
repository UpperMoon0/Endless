package com.nstut.endless.vertical;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/** Delegates vanilla lighting inside the dense core and serves sparse light outside it. */
public final class EndlessLayerLightEventListener implements LayerLightEventListener {
    private final Level level;
    private final LightLayer layer;
    private final LayerLightEventListener delegate;

    public EndlessLayerLightEventListener(Level level, LightLayer layer, LayerLightEventListener delegate) {
        this.level = level;
        this.layer = layer;
        this.delegate = delegate;
    }

    @Override
    public DataLayer getDataLayerData(SectionPos pos) {
        return delegate.getDataLayerData(pos);
    }

    @Override
    public int getLightValue(BlockPos pos) {
        if (!EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            return delegate.getLightValue(pos);
        }
        int sparse = EndlessVerticalEngine.world(level).getBrightness(layer, pos);
        // Within the raw packed envelope vanilla guard light can still provide
        // useful boundary illumination from the dense core. Outside it, never
        // call the packed-long engine because neighbor math can wrap Y.
        if (pos.getY() >= -2048 && pos.getY() <= 2047) {
            return Math.max(sparse, delegate.getLightValue(pos));
        }
        return sparse;
    }

    @Override
    public void checkBlock(BlockPos pos) {
        if (!EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            delegate.checkBlock(pos);
        }
    }

    @Override
    public boolean hasLightWork() {
        return delegate.hasLightWork();
    }

    @Override
    public int runLightUpdates() {
        return delegate.runLightUpdates();
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean empty) {
        // SectionPos is safe throughout Endless' documented logical range.
        delegate.updateSectionStatus(pos, empty);
    }

    @Override
    public void setLightEnabled(ChunkPos pos, boolean enabled) {
        delegate.setLightEnabled(pos, enabled);
    }

    @Override
    public void propagateLightSources(ChunkPos pos) {
        delegate.propagateLightSources(pos);
    }
}
