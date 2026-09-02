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
    void millionScaleRangeSurvivesNormalization() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(-5_000_000);
        config.setMaxBuildHeight(5_000_000);
        config.clamp();
        assertEquals(-5_000_000, config.getMinBuildHeight());
        assertEquals(5_000_000, config.getMaxBuildHeight());
    }

    @Test
    void clampsOnlyAtSparseRepresentationEnvelope() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(-9_000_000);
        config.setMaxBuildHeight(9_000_000);
        config.clamp();
        assertEquals(-8_000_000, config.getMinBuildHeight());
        assertEquals(8_000_000, config.getMaxBuildHeight());
    }

    @Test
    void maxIsExclusiveSoEnvelopeTopIsY7999999() {
        assertEquals(7_999_999, EndlessConfig.MAX_BUILD_HEIGHT_MAX - 1);
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
        config.setMinBuildHeight(-129);
        config.setMaxBuildHeight(620);
        config.clamp();
        assertEquals(-144, config.getMinBuildHeight(),
            "minBuildHeight should snap down to a 16-block section boundary");
        assertEquals(624, config.getMaxBuildHeight(),
            "maxBuildHeight should snap up to a 16-block section boundary");
    }

    @Test
    void clampSurvivesIntegerExtremes() {
        EndlessConfig.BuildHeightConfig config = new EndlessConfig.BuildHeightConfig();
        config.setMinBuildHeight(Integer.MIN_VALUE);
        config.setMaxBuildHeight(Integer.MAX_VALUE);
        config.clamp();
        assertEquals(-8_000_000, config.getMinBuildHeight());
        assertEquals(8_000_000, config.getMaxBuildHeight(),
            "maxBuildHeight must clamp to the sparse envelope, not reset via overflow");
    }

    @Test
    void denseCapRemainsIndependentOfLogicalEnvelope() {
        int denseSpan = EndlessConfig.DENSE_MAX_BUILD_HEIGHT - EndlessConfig.DENSE_MIN_BUILD_HEIGHT;
        assertEquals(EndlessConfig.MAX_DENSE_SECTIONS * 16, denseSpan);
        assertTrue(EndlessConfig.MAX_BUILD_HEIGHT_MAX - EndlessConfig.MIN_BUILD_HEIGHT_MIN > denseSpan);
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

        // v0.5 no longer clamps logical user intent to the old dense envelope.
        // Migration separately projects/inspects this range before using it as
        // the world's persistent vanilla dense core.
        assertEquals(-2048, config.getBuildHeight().getMinBuildHeight());
        assertEquals(2048, config.getBuildHeight().getMaxBuildHeight());
        assertEquals(legacyJson, Files.readString(configFile));

        EndlessConfig.BuildHeightConfig raw = config.getRawLoadedBuildHeight();
        assertNotNull(raw);
        assertEquals(-2048, raw.getMinBuildHeight());
        assertEquals(2048, raw.getMaxBuildHeight());
    }

    @Test
    void loadPreservesConfiguredMillionScaleRange(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("endless.json");
        Files.writeString(configFile, "{\"buildHeight\":{\"minBuildHeight\":-1000000,\"maxBuildHeight\":1000000}}");

        EndlessConfig config = new EndlessConfig();
        config.load(tempDir);

        assertEquals(-1_000_000, config.getBuildHeight().getMinBuildHeight());
        assertEquals(1_000_000, config.getBuildHeight().getMaxBuildHeight());
        assertTrue(Files.readString(configFile).contains("1000000"),
            "launch-time normalization must not rewrite a valid million-scale config to defaults/dense limits");
    }
}
