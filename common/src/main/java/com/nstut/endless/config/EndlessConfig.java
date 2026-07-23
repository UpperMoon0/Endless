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

/**
 * Configuration handler for the Endless mod.
 */
public class EndlessConfig {
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILENAME = "endless.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int MIN_BUILD_HEIGHT_MIN = -4096;
    public static final int MAX_BUILD_HEIGHT_MAX = 8192;

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

            try (FileReader reader = new FileReader(file)) {
                EndlessConfig loadedConfig = GSON.fromJson(reader, EndlessConfig.class);
                if (loadedConfig != null) {
                    this.buildHeight = loadedConfig.buildHeight;
                    this.buildHeight.clamp();
                }
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
