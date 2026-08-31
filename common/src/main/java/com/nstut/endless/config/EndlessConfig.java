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
     * Hard limits on the supported build height. They match the packed BlockPos
     * envelope: vanilla packs Y into 12 bits, so any coordinate outside
     * [-2048, 2048) silently wraps when a BlockPos long or a block-update packet
     * is produced. Values beyond that envelope are not usable in production until
     * position serialization itself is extended, so the config clamps to it.
     */
    public static final int MIN_BUILD_HEIGHT_MIN = -2048;
    public static final int MAX_BUILD_HEIGHT_MAX = 2048;

    /**
     * Hard cap on section count per chunk. 4096 blocks (the full packed-Y
     * envelope) is 256 sections; the config cannot request more.
     */
    public static final int MAX_SECTIONS = 256;

    private static EndlessConfig instance;

    private BuildHeightConfig buildHeight = new BuildHeightConfig();

    public static EndlessConfig getInstance() {
        if (instance == null) {
            instance = new EndlessConfig();
        }
        return instance;
    }

    public void load() {
        Path configDir = Paths.get(CONFIG_DIR);
        Path configFile = configDir.resolve(CONFIG_FILENAME);

        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            File file = configFile.toFile();

            if (!file.exists()) {
                save();
                return;
            }

            BuildHeightConfig loadedHeight;
            try (FileReader reader = new FileReader(file)) {
                EndlessConfig loadedConfig = GSON.fromJson(reader, EndlessConfig.class);
                loadedHeight = loadedConfig != null ? loadedConfig.buildHeight : null;
            } catch (RuntimeException e) {
                // Gson parse errors are runtime exceptions (malformed JSON, wrong
                // types) and must never kill startup. Keep defaults and preserve
                // the broken file so the user can recover it.
                Path broken = configFile.resolveSibling(CONFIG_FILENAME + ".broken");
                try {
                    Files.copy(configFile, broken, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException copyError) {
                    System.err.println("Endless: failed to back up broken config: " + copyError.getMessage());
                }
                System.err.println("Endless: malformed config (" + e.getMessage()
                    + "), using defaults. Original saved as " + broken.getFileName());
                save();
                return;
            }

            if (loadedHeight == null) {
                System.err.println("Endless: config is missing the buildHeight section, using defaults");
                save();
                return;
            }

            this.buildHeight = loadedHeight;
            int beforeMin = buildHeight.getMinBuildHeight();
            int beforeMax = buildHeight.getMaxBuildHeight();
            buildHeight.clamp();
            if (buildHeight.getMinBuildHeight() != beforeMin || buildHeight.getMaxBuildHeight() != beforeMax) {
                save();
            }
        } catch (IOException e) {
            System.err.println("Failed to load Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void save() {
        Path configDir = Paths.get(CONFIG_DIR);
        Path configFile = configDir.resolve(CONFIG_FILENAME);

        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            try (FileWriter writer = new FileWriter(configFile.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public BuildHeightConfig getBuildHeight() {
        return buildHeight;
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

        public void clamp() {
            // Section math (getSectionIndex, section arrays, lighting) assumes the
            // bounds are 16-aligned; snap outward before applying the envelope.
            minBuildHeight = minBuildHeight & ~15;
            maxBuildHeight = (maxBuildHeight + 15) & ~15;
            minBuildHeight = Math.max(minBuildHeight, MIN_BUILD_HEIGHT_MIN);
            maxBuildHeight = Math.min(maxBuildHeight, MAX_BUILD_HEIGHT_MAX);
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
