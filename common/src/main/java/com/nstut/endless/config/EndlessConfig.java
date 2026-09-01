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

/**
 * Configuration handler for the Endless mod.
 */
public class EndlessConfig {
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILENAME = "endless.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Hard limits on the supported build height. Vanilla intentionally reserves
     * a 16-block guard band at each edge of the packed BlockPos envelope
     * (DimensionType spans [-2032, 2032) of the raw 12-bit Y range
     * [-2048, 2048)). Engine operations like BlockPos.offset and light
     * propagation step to neighboring packed positions; without the guard band
     * the neighbor of the top block would wrap to the bottom of the world.
     * The config clamps to the same banded range vanilla dimensions use.
     */
    public static final int MIN_BUILD_HEIGHT_MIN = -2032;
    public static final int MAX_BUILD_HEIGHT_MAX = 2032;

    /**
     * Hard cap on section count per chunk. 4064 blocks (the guard-banded
     * envelope) is 254 sections; the config cannot request more.
     */
    public static final int MAX_SECTIONS = 254;

    private static EndlessConfig instance;

    private BuildHeightConfig buildHeight = new BuildHeightConfig();

    // Never serialize migration bookkeeping. rawLoadedBuildHeight is the exact
    // pre-clamp evidence from disk and must survive in memory until the server
    // has classified a pre-v0.4 world. Rewriting the file before that point can
    // destroy the only record of a legacy range such as [-2048, 2048).
    private transient BuildHeightConfig rawLoadedBuildHeight;
    private transient boolean normalizationPending;

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
                // Preserve malformed input and use defaults in memory, but do
                // not rewrite endless.json yet. A played pre-v0.4 world may
                // depend on information in that file, so startup migration must
                // classify the world before any normalization/default write.
                Path broken = configFile.resolveSibling(CONFIG_FILENAME + ".broken");
                try {
                    Files.copy(configFile, broken, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("Endless: malformed config (" + e.getMessage()
                        + "), using defaults in memory. Original copied to " + broken.getFileName()
                        + "; endless.json is left untouched until world migration is classified.");
                } catch (IOException copyError) {
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
            rawLoadedBuildHeight = buildHeight.copy();
        } catch (IOException e) {
            System.err.println("Failed to save Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Persist a normalized config only after world migration has safely
     * completed. Calling this before classification would erase legacy range
     * evidence and is intentionally avoided by the startup path.
     */
    public void saveNormalizedIfNeeded() {
        if (normalizationPending) {
            save();
        }
    }

    public BuildHeightConfig getBuildHeight() {
        return buildHeight;
    }

    /**
     * Exact build-height values parsed from disk before v0.4 clamping/alignment,
     * or null when no trustworthy buildHeight section could be read.
     */
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
            // Section math (getSectionIndex, section arrays, lighting) assumes the
            // bounds are 16-aligned; snap outward before applying the envelope.
            // Clamp first so huge JSON integers cannot overflow during alignment,
            // then align in long math and re-clamp.
            minBuildHeight = Math.max(minBuildHeight, MIN_BUILD_HEIGHT_MIN);
            maxBuildHeight = Math.min(maxBuildHeight, MAX_BUILD_HEIGHT_MAX);
            long min = (long) minBuildHeight & ~15L;
            long max = ((long) maxBuildHeight + 15L) & ~15L;
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
