package com.nstut.endless.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Configuration handler for the Endless mod. */
public class EndlessConfig {
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILENAME = "endless.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** v0.5 logical/sparse build envelope, kept inside Endless' signed-section representation. */
    public static final int MIN_BUILD_HEIGHT_MIN = -8_000_000;
    public static final int MAX_BUILD_HEIGHT_MAX = 8_000_000;

    /**
     * Vanilla-compatible dense Anvil envelope retained for terrain/chunk arrays and v0.4 migration.
     * This is deliberately much smaller than the user-configurable logical range.
     */
    public static final int DENSE_MIN_BUILD_HEIGHT = -2032;
    public static final int DENSE_MAX_BUILD_HEIGHT = 2032;
    public static final int MAX_DENSE_SECTIONS = 254;

    /** @deprecated Use {@link #MAX_DENSE_SECTIONS}; logical height is no longer section-array-sized. */
    @Deprecated
    public static final int MAX_SECTIONS = MAX_DENSE_SECTIONS;

    private static EndlessConfig instance;
    private BuildHeightConfig buildHeight = new BuildHeightConfig();

    // Migration bookkeeping is deliberately transient. rawLoadedBuildHeight
    // preserves the exact pre-normalization evidence from disk until a pre-v0.4
    // world has been classified. normalizationWriteAllowed keeps the older
    // safety guarantee that a malformed original is never overwritten when its
    // backup could not be created.
    private transient BuildHeightConfig rawLoadedBuildHeight;
    private transient boolean normalizationPending;
    private transient boolean normalizationWriteAllowed = true;

    public static EndlessConfig getInstance() {
        if (instance == null) {
            instance = new EndlessConfig();
        }
        return instance;
    }

    public void load() {
        load(Paths.get(CONFIG_DIR));
    }

    /** Package-private path overload for regression tests. */
    void load(Path configDir) {
        Path configFile = configDir.resolve(CONFIG_FILENAME);
        buildHeight = new BuildHeightConfig();
        rawLoadedBuildHeight = null;
        normalizationPending = false;
        normalizationWriteAllowed = true;

        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            File file = configFile.toFile();
            if (!file.exists()) {
                rawLoadedBuildHeight = buildHeight.copy();
                save(configDir);
                return;
            }

            BuildHeightConfig loadedHeight;
            try (FileReader reader = new FileReader(file)) {
                EndlessConfig loadedConfig = GSON.fromJson(reader, EndlessConfig.class);
                loadedHeight = loadedConfig != null ? loadedConfig.buildHeight : null;
            } catch (RuntimeException e) {
                Path broken = configFile.resolveSibling(CONFIG_FILENAME + ".broken");
                try {
                    Files.copy(configFile, broken, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("Endless: malformed config (" + e.getMessage()
                        + "), using defaults in memory. Original copied to " + broken.getFileName()
                        + "; endless.json is left untouched until world migration is classified.");
                } catch (IOException copyError) {
                    normalizationWriteAllowed = false;
                    System.err.println("Endless: malformed config (" + e.getMessage()
                        + "), using defaults in memory; failed to create backup ("
                        + copyError.getMessage() + "), original left untouched at " + configFile);
                }
                normalizationPending = true;
                return;
            }

            if (loadedHeight == null) {
                System.err.println("Endless: config is missing the buildHeight section, using defaults in memory; "
                    + "the file is left untouched until world migration is classified");
                normalizationPending = true;
                return;
            }

            rawLoadedBuildHeight = loadedHeight.copy();
            buildHeight = loadedHeight;
            int beforeMin = buildHeight.getMinBuildHeight();
            int beforeMax = buildHeight.getMaxBuildHeight();
            buildHeight.clamp();
            normalizationPending = buildHeight.getMinBuildHeight() != beforeMin
                || buildHeight.getMaxBuildHeight() != beforeMax;
            if (normalizationPending) {
                System.err.println("Endless: config range [" + beforeMin + ", " + beforeMax
                    + ") normalizes to [" + buildHeight.getMinBuildHeight() + ", "
                    + buildHeight.getMaxBuildHeight() + "); preserving the raw file until world migration is classified");
            }
        } catch (IOException e) {
            System.err.println("Failed to load Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void save() {
        save(Paths.get(CONFIG_DIR));
    }

    /** Package-private path overload for regression tests. */
    void save(Path configDir) {
        Path configFile = configDir.resolve(CONFIG_FILENAME);
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            try (FileWriter writer = new FileWriter(configFile.toFile())) {
                GSON.toJson(this, writer);
            }
            normalizationPending = false;
            normalizationWriteAllowed = true;
            rawLoadedBuildHeight = buildHeight.copy();
        } catch (IOException e) {
            System.err.println("Failed to save Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Persist normalization only after world migration is classified safely. */
    public void saveNormalizedIfNeeded() {
        if (normalizationPending && normalizationWriteAllowed) {
            save();
        }
    }

    public BuildHeightConfig getBuildHeight() {
        return buildHeight;
    }

    /** Exact values parsed from disk before v0.5 envelope/alignment normalization. */
    public BuildHeightConfig getRawLoadedBuildHeight() {
        return rawLoadedBuildHeight == null ? null : rawLoadedBuildHeight.copy();
    }

    public static class BuildHeightConfig {
        private int minBuildHeight = -64;
        private int maxBuildHeight = 320;

        public int getMinBuildHeight() {
            return minBuildHeight;
        }

        public int getMaxBuildHeight() {
            return maxBuildHeight;
        }

        public BuildHeightConfig copy() {
            BuildHeightConfig copy = new BuildHeightConfig();
            copy.minBuildHeight = minBuildHeight;
            copy.maxBuildHeight = maxBuildHeight;
            return copy;
        }

        public void clamp() {
            long min = Math.max((long) minBuildHeight, MIN_BUILD_HEIGHT_MIN);
            long max = Math.min((long) maxBuildHeight, MAX_BUILD_HEIGHT_MAX);
            min &= ~15L;
            max = (max + 15L) & ~15L;
            minBuildHeight = (int) Math.max(min, MIN_BUILD_HEIGHT_MIN);
            maxBuildHeight = (int) Math.min(max, MAX_BUILD_HEIGHT_MAX);
            if (minBuildHeight >= maxBuildHeight) {
                System.err.println("Endless: minBuildHeight (" + minBuildHeight + ") >= maxBuildHeight ("
                    + maxBuildHeight + "), resetting to defaults");
                minBuildHeight = -64;
                maxBuildHeight = 320;
            }
        }

        public void setMinBuildHeight(int minBuildHeight) {
            this.minBuildHeight = minBuildHeight;
        }

        public void setMaxBuildHeight(int maxBuildHeight) {
            this.maxBuildHeight = maxBuildHeight;
        }
    }
}
