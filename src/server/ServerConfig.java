package server;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Server configuration loaded from server.properties
 */
public class ServerConfig {
    private final Properties properties;
    
    // Network settings
    private int port;
    private int maxRequestsPerSecond;
    
    // World settings
    private String worldName;
    private long worldSeed;
    private int maxCoordinate;
    
    // Memory settings
    private int serverMaxChunks;
    private double emergencyThreshold;
    
    // Persistence settings
    private int autosaveIntervalSeconds;
    
    public ServerConfig() {
        this.properties = new Properties();
        loadDefaults();
    }
    
    /**
     * Load configuration from file, using defaults for missing values
     */
    public void load(String configPath) {
        Path path = Paths.get(configPath);
        
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
                System.out.println("Loaded configuration from: " + configPath);
            } catch (IOException e) {
                System.err.println("Failed to load config file, using defaults: " + e.getMessage());
            }
        } else {
            System.out.println("Config file not found, using defaults. Creating: " + configPath);
            saveDefaults(configPath);
        }
        
        parseProperties();
    }
    
    /**
     * Set default values
     */
    private void loadDefaults() {
        // Network
        properties.setProperty("server.port", "25565");
        properties.setProperty("server.max_requests_per_second", "10000");
        
        // World
        properties.setProperty("world.name", "world");
        properties.setProperty("world.seed", "123456");
        properties.setProperty("world.max_coordinate", "100000");
        
        // Generation - MUCH larger continents for RTS gameplay
        properties.setProperty("generation.continent_scale", "0.00008");
        properties.setProperty("generation.terrain_scale", "0.002");
        properties.setProperty("generation.detail_scale", "0.01");
        properties.setProperty("generation.domain_warp_strength", "30");
        
        // Memory
        properties.setProperty("memory.server_max_chunks", "10000");
        properties.setProperty("memory.emergency_threshold", "0.9");
        
        // Persistence
        properties.setProperty("persistence.autosave_interval_seconds", "30");
        
        parseProperties();
    }
    
    /**
     * Save default configuration to file
     */
    private void saveDefaults(String configPath) {
        try (OutputStream output = Files.newOutputStream(Paths.get(configPath))) {
            properties.store(output, "Server Configuration - Generated Defaults");
        } catch (IOException e) {
            System.err.println("Failed to save default config: " + e.getMessage());
        }
    }
    
    /**
     * Parse properties into typed fields
     */
    private void parseProperties() {
        // Network
        port = Integer.parseInt(properties.getProperty("server.port", "25565"));
        maxRequestsPerSecond = Integer.parseInt(properties.getProperty("server.max_requests_per_second", "10000"));
        
        // World
        worldName = properties.getProperty("world.name", "world");
        worldSeed = Long.parseLong(properties.getProperty("world.seed", "123456"));
        maxCoordinate = Integer.parseInt(properties.getProperty("world.max_coordinate", "100000"));
        
        // Memory
        serverMaxChunks = Integer.parseInt(properties.getProperty("memory.server_max_chunks", "10000"));
        emergencyThreshold = Double.parseDouble(properties.getProperty("memory.emergency_threshold", "0.9"));
        
        // Persistence
        autosaveIntervalSeconds = Integer.parseInt(properties.getProperty("persistence.autosave_interval_seconds", "30"));
    }
    
    // Getters
    public int getPort() { return port; }
    public int getMaxRequestsPerSecond() { return maxRequestsPerSecond; }
    public String getWorldName() { return worldName; }
    public long getWorldSeed() { return worldSeed; }
    public int getMaxCoordinate() { return maxCoordinate; }
    public int getServerMaxChunks() { return serverMaxChunks; }
    public double getEmergencyThreshold() { return emergencyThreshold; }
    public int getAutosaveIntervalSeconds() { return autosaveIntervalSeconds; }
    
    /**
     * Display current configuration
     */
    public void printConfig() {
        System.out.println("=== Server Configuration ===");
        System.out.println("Network:");
        System.out.println("  Port: " + port);
        System.out.println("  Max Requests/sec: " + maxRequestsPerSecond);
        System.out.println("World:");
        System.out.println("  Name: " + worldName);
        System.out.println("  Seed: " + worldSeed);
        System.out.println("  Max Coordinate: ±" + maxCoordinate);
        System.out.println("Memory:");
        System.out.println("  Max Chunks: " + serverMaxChunks);
        System.out.println("  Emergency Threshold: " + (emergencyThreshold * 100) + "%");
        System.out.println("Persistence:");
        System.out.println("  Autosave Interval: " + autosaveIntervalSeconds + "s");
        System.out.println("===========================");
    }
}
