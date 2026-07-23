package com.nstut.endless.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EndlessConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsAreVanilla() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        assertEquals(-64, config.getMinBuildHeight());
        assertEquals(320, config.getMaxBuildHeight());
    }

    @Test
    void clampsMinBuildHeight_toAllowedRange() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(-8192);
        config.clamp();
        assertEquals(-4096, config.getMinBuildHeight(),
            "minBuildHeight below -4096 should clamp to -4096");
    }

    @Test
    void clampsMaxBuildHeight_toAllowedRange() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMaxBuildHeight(32768);
        config.clamp();
        assertEquals(8192, config.getMaxBuildHeight(),
            "maxBuildHeight above 8192 should clamp to 8192");
    }

    @Test
    void resetsWhenMinExceedsMax() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMaxBuildHeight(10);
        config.setMinBuildHeight(10);
        config.clamp();
        assertEquals(-64, config.getMinBuildHeight(),
            "Invalid config should reset min to -64");
        assertEquals(320, config.getMaxBuildHeight(),
            "Invalid config should reset max to 320");
    }

    @Test
    void clampsRespectsValidCustomRange() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(-128);
        config.setMaxBuildHeight(620);
        config.clamp();
        assertEquals(-128, config.getMinBuildHeight());
        assertEquals(620, config.getMaxBuildHeight());
    }

    @Test
    void legacyConfig_withRemoveBuildHeightLimit_isSilentlyIgnored() {
        String legacyJson = "{\"buildHeight\":{\"minBuildHeight\":-64,\"maxBuildHeight\":620,"
            + "\"removeBuildHeightLimit\":true}}";
        Gson gson = new GsonBuilder().create();
        EndlessConfig loaded = gson.fromJson(legacyJson, EndlessConfig.class);
        assertNotNull(loaded);
        assertEquals(620, loaded.getBuildHeight().getMaxBuildHeight(),
            "Legacy config with removeBuildHeightLimit should parse maxBuildHeight");
        assertEquals(-64, loaded.getBuildHeight().getMinBuildHeight());
    }

    @Test
    void legacyConfig_withFlag_onDeserialization_clampsAfterLoad() {
        String legacyJson = "{\"buildHeight\":{\"minBuildHeight\":-64,\"maxBuildHeight\":620,"
            + "\"removeBuildHeightLimit\":true}}";
        Gson gson = new GsonBuilder().create();
        EndlessConfig loaded = gson.fromJson(legacyJson, EndlessConfig.class);
        loaded.getBuildHeight().clamp();
        assertEquals(620, loaded.getBuildHeight().getMaxBuildHeight());
    }
}
