package common;

import java.awt.Color;

/**
 * Represents different terrain types with associated colors and properties
 * Now uses height, temperature, and moisture to determine biomes
 */
public enum TerrainType {
    // Ocean biomes (height < 0.4)
    DEEP_OCEAN(new Color(0, 40, 90), "Deep Ocean"),
    OCEAN(new Color(30, 100, 160), "Ocean"), // Matched to River/Shallow
    SHALLOW_WATER(new Color(30, 100, 160), "Shallow Water"),
    
    // Beach/coast (height ~0.4-0.42)
    BEACH(new Color(230, 210, 120), "Beach"),
    
    // Low elevation biomes (height 0.42-0.6)
    TUNDRA(new Color(180, 200, 200), "Tundra"),
    TAIGA(new Color(60, 100, 80), "Taiga"),
    GRASSLAND(new Color(80, 170, 70), "Grassland"),
    DESERT(new Color(220, 200, 140), "Desert"),
    SAVANNA(new Color(150, 170, 80), "Savanna"),
    TROPICAL_FOREST(new Color(30, 120, 40), "Tropical Forest"),
    TEMPERATE_FOREST(new Color(40, 140, 50), "Temperate Forest"),
    
    // Mid elevation (height 0.6-0.75)
    SHRUBLAND(new Color(140, 150, 90), "Shrubland"),
    WOODLAND(new Color(70, 120, 60), "Woodland"),
    
    // High elevation (height 0.75-0.85)
    HILLS(new Color(120, 130, 100), "Hills"),
    MOUNTAIN(new Color(140, 140, 130), "Mountain"),
    
    // Very high elevation (height > 0.85)
    SNOW_MOUNTAIN(new Color(230, 235, 240), "Snow Mountain"),
    
    // Special features
    RIVER(new Color(30, 100, 160), "River"), // match shallow water color
    
    // Add new biomes for smoother transitions and diversity
    LAKE(new Color(30, 100, 160), "Lake"), // Matched to River/Ocean
    SWAMP(new Color(100, 150, 100), "Swamp"),
    STEPPE(new Color(120, 180, 100), "Steppe"),
    RAINFOREST(new Color(20, 100, 20), "Rainforest");

    private final Color color;
    private final String displayName;

    TerrainType(Color color, String displayName) {
        this.color = color;
        this.displayName = displayName;
    }

    public Color getColor() {
        return color;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Determines terrain type based on height, temperature, and moisture
     * @param height 0.0 to 1.0 (0-0.4 = ocean, 0.4-1.0 = land)
     * @param temperature 0.0 to 1.0 (0 = freezing, 1 = hot)
     * @param moisture 0.0 to 1.0 (0 = dry, 1 = wet)
     */
    public static TerrainType fromEnvironment(double height, double temperature, double moisture, boolean isRiver) {
        // Rivers override other terrain at almost all land elevations
        // Stops effectively at Snow Mountains (> 0.92)
        if (isRiver && height > 0.4 && height < 0.92) {
            return RIVER;
        }
        
        // Ocean depths
        if (height < 0.3) return DEEP_OCEAN;
        if (height < 0.38) return OCEAN;
        if (height < 0.42) return SHALLOW_WATER;
        
        // Very high mountains with snow (more restrictive)
        if (height > 0.92) {
            return SNOW_MOUNTAIN;
        }
        
        // High mountains (less snow)
        if (height > 0.85) {
            if (temperature < 0.25) { // Much colder required for snow
                return SNOW_MOUNTAIN;
            }
            return MOUNTAIN;
        }
        
        // Mid-high elevation
        if (height > 0.75) {
            if (temperature < 0.3) {
                return MOUNTAIN; // Cold mountains without snow
            }
            return moisture < 0.3 ? SHRUBLAND : WOODLAND;
        }
        
        // Hills (fixed duplicate threshold)
        if (height > 0.65) {
            return HILLS;
        }
        
        // Upper plains
        if (height > 0.55) {
            if (moisture < 0.35) return SHRUBLAND;
            return WOODLAND;
        }
        
        // Low elevation - biome based on temperature and moisture
        // Cold (arctic/subarctic) - much more restrictive
        if (temperature < 0.15 && height > 0.5) { // Only very cold and higher elevation
            return TUNDRA;
        }
        
        // Cool (boreal)
        if (temperature < 0.3) {
            return moisture > 0.4 ? TAIGA : GRASSLAND; // Replace tundra with grassland
        }
        
        // Temperate
        if (temperature < 0.6) { // Lowered from 0.65 to give more room for hot biomes
            if (moisture < 0.3) return GRASSLAND;
            if (moisture < 0.6) return TEMPERATE_FOREST;
            return TEMPERATE_FOREST;
        }
        
        // Warm/Hot (tropical/subtropical)
        if (moisture < 0.45) return DESERT; // Increased drastically to 0.45 to force deserts
        if (moisture < 0.65) return SAVANNA; // Increased from 0.5
        
        // Add new biome logic for lakes and swamps
        if (height > 0.4 && height < 0.5 && moisture > 0.7) {
            return SWAMP;
        }

        // Add logic for steppe biome
        if (height > 0.5 && height < 0.6 && moisture < 0.3 && temperature > 0.4) {
            return STEPPE;
        }

        // Add logic for rainforest biome
        if (temperature > 0.7 && moisture > 0.7) {
            return RAINFOREST;
        }

        // Adjust snow biome placement
        if (height > 0.92) {
            return SNOW_MOUNTAIN; // Snow only on very high mountains
        }

        // Tundra biome for cold, low-moisture areas
        if (temperature < 0.2 && moisture < 0.4) {
            return TUNDRA;
        }

        // Default case to ensure all paths return a value
        return GRASSLAND;
    }

    /**
     * Returns a color with variation for more natural appearance
     */
    public Color getColorWithVariation(int x, int y) {
       // Removed noise variation to prevent "dirty" look
       return color;
    }
}
