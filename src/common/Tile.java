package common;

import java.io.Serializable;

/**
 * Represents a single tile in the world map
 */
public class Tile implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private TerrainType terrainType;
    private double height; // 0.0 to 1.0
    private double temperature; // Affects crops/biome
    private double moisture; // Affects crops/biome
    
    // Nation/MMORPG Properties
    private long ownerId = -1; // -1 = Unclaimed
    private int structureId = 0; // 0 = None. Maps to a Structure registry.
    private byte rotation = 0; // Building rotation
    private int contentAmount = 0; // Resources count / Storage
    
    public Tile(TerrainType terrainType, double height) {
        this.terrainType = terrainType;
        this.height = height;
        this.temperature = 0.5;
        this.moisture = 0.5;
    }

    // ============================
    //      Gameplay Methods
    // ============================
    
    public boolean isClaimed() {
        return ownerId != -1;
    }

    public boolean hasStructure() {
        return structureId != 0;
    }

    // ============================
    //      Getters / Setters
    // ============================

    public TerrainType getTerrainType() {
        return terrainType;
    }

    public void setTerrainType(TerrainType terrainType) {
        this.terrainType = terrainType;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getMoisture() {
        return moisture;
    }

    public void setMoisture(double moisture) {
        this.moisture = moisture;
    }
    
    public long getOwnerId() { return ownerId; }
    public void setOwnerId(long ownerId) { this.ownerId = ownerId; }
    
    public int getStructureId() { return structureId; }
    public void setStructureId(int structureId) { this.structureId = structureId; }
    
    public byte getRotation() { return rotation; }
    public void setRotation(byte rotation) { this.rotation = rotation; }
    
    public int getContentAmount() { return contentAmount; }
    public void setContentAmount(int contentAmount) { this.contentAmount = contentAmount; }
}
