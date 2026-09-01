package com.nstut.endless.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EndlessConfigTest {

    @Test
    void defaultsAreVanilla() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        assertEquals(-64, config.getMinBuildHeight());
        assertEquals(320, config.getMaxBuildHeight());
    }

    @Test
    void clampsMinBuildHeight_toPackedBlockPosEnvelope() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(-5000000);
        config.clamp();
        assertEquals(-2032, config.getMinBuildHeight(),
            "minBuildHeight below the guarded packed Y envelope must clamp to -2032");
    }

    @Test
    void clampsMaxBuildHeight_toPackedBlockPosEnvelope() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMaxBuildHeight(5000000);
        config.clamp();
        assertEquals(2032, config.getMaxBuildHeight(),
            "maxBuildHeight above the guarded packed Y envelope must clamp to 2032");
    }

    @Test
    void maxIsExclusiveSoEnvelopeTopIsY2031() {
        assertEquals(2031, EndlessConfig.MAX_BUILD_HEIGHT_MAX - 1);
    }

    @Test
    void resetsWhenRangeIsInverted() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(1000);
        config.setMaxBuildHeight(200);
        config.clamp();
        assertEquals(-64, config.getMinBuildHeight(),
            "Inverted config should reset min to -64");
        assertEquals(320, config.getMaxBuildHeight(),
            "Inverted config should reset max to 320");
    }

    @Test
    void normalizesToSectionBoundaries() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(-128);
        config.setMaxBuildHeight(620);
        config.clamp();
        assertEquals(-128, config.getMinBuildHeight());
        assertEquals(624, config.getMaxBuildHeight(),
            "maxBuildHeight should snap up to a 16-block section boundary");
    }

    @Test
    void clampSurvivesIntegerMaxValue() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(Integer.MIN_VALUE);
        config.setMaxBuildHeight(Integer.MAX_VALUE);
        config.clamp();
        assertEquals(-2032, config.getMinBuildHeight());
        assertEquals(2032, config.getMaxBuildHeight(),
            "maxBuildHeight must clamp to the envelope, not reset via overflow");
    }

    @Test
    void sectionCapCoversFullEnvelope() {
        int envelopeSpan = EndlessConfig.MAX_BUILD_HEIGHT_MAX - EndlessConfig.MIN_BUILD_HEIGHT_MIN;
        assertEquals(EndlessConfig.MAX_SECTIONS * 16, envelopeSpan,
            "getHeight() caps at MAX_SECTIONS*16, so the cap must cover the whole envelope");
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
        assertEquals(624, loaded.getBuildHeight().getMaxBuildHeight());
    }

    @Test
    void rawLegacyConfigIsNotOverwrittenBeforeMigrationClassification(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("endless.json");
        String legacyJson = "{\n"
            + "  \"buildHeight\": {\n"
            + "    \"minBuildHeight\": -2048,\n"
            + "    \"maxBuildHeight\": 2048\n"
            + "  }\n"
            + "}\n";
        Files.writeString(configFile, legacyJson);

        EndlessConfig config = new EndlessConfig();
        config.load(tempDir);

        // Runtime access is safe immediately, but the disk evidence remains
        // untouched until EndlessHeights has classified the world.
        assertEquals(-2032, config.getBuildHeight().getMinBuildHeight());
        assertEquals(2032, config.getBuildHeight().getMaxBuildHeight());
        assertEquals(legacyJson, Files.readString(configFile));

        EndlessConfig.BuildHeightConfig raw = config.getRawLoadedBuildHeight();
        assertNotNull(raw);
        assertEquals(-2048, raw.getMinBuildHeight());
        assertEquals(2048, raw.getMaxBuildHeight());
    }
}
