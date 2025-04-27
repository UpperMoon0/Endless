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
    
    private static EndlessConfig instance;
    
    // Config values
    private BuildHeightConfig buildHeight = new BuildHeightConfig();
    
    /**
     * Get the singleton instance of the config.
     */
    public static EndlessConfig getInstance() {
        if (instance == null) {
            instance = new EndlessConfig();
        }
        return instance;
    }
    
    /**
     * Load the configuration from file or create default if it doesn't exist.
     */
    public void load() {
        Path configDir = Paths.get(CONFIG_DIR);
        Path configFile = configDir.resolve(CONFIG_FILENAME);
        
        try {
            // Create config directory if it doesn't exist
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            
            File file = configFile.toFile();
            
            // Create default config if file doesn't exist
            if (!file.exists()) {
                save(); // Save default values
                return;
            }
            
            // Read and parse the config file
            try (FileReader reader = new FileReader(file)) {
                EndlessConfig loadedConfig = GSON.fromJson(reader, EndlessConfig.class);
                if (loadedConfig != null) {
                    this.buildHeight = loadedConfig.buildHeight;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Save the current configuration to file.
     */
    public void save() {
        Path configDir = Paths.get(CONFIG_DIR);
        Path configFile = configDir.resolve(CONFIG_FILENAME);
        
        try {
            // Create config directory if it doesn't exist
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            
            // Write config to file
            try (FileWriter writer = new FileWriter(configFile.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save Endless config: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get the build height configuration.
     */
    public BuildHeightConfig getBuildHeight() {
        return buildHeight;
    }
    
    /**
     * Configuration for build height limits.
     */
    public static class BuildHeightConfig {
        private int minBuildHeight = -64;  
        private int maxBuildHeight = 320; 
        private boolean removeBuildHeightLimit = false;
        
        public int getMinBuildHeight() {
            return removeBuildHeightLimit ? Integer.MIN_VALUE / 2 : minBuildHeight;
        }
          public int getMaxBuildHeight() {
            return removeBuildHeightLimit ? Integer.MAX_VALUE / 2 : maxBuildHeight;
        }
        
        public boolean isRemoveBuildHeightLimit() {
            return removeBuildHeightLimit;
        }
        
        public void setMinBuildHeight(int minBuildHeight) {
            this.minBuildHeight = minBuildHeight;
        }
        
        public void setMaxBuildHeight(int maxBuildHeight) {
            this.maxBuildHeight = maxBuildHeight;
        }
        
        public void setRemoveBuildHeightLimit(boolean removeBuildHeightLimit) {
            this.removeBuildHeightLimit = removeBuildHeightLimit;
        }
    }
}
