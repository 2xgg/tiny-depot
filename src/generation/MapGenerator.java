package generation;

import common.*;
import generation.biomes.*;

/**
 * Realistic 2.5D map generator with large continental landmasses.
 * Uses a "Macro-First" approach: Continents have a global climate (e.g., Desert Continent)
 * that dictates local features, preventing biome inconsistency.
 */
public class MapGenerator {
    private final long seed;
    
    // Low Frequency - determines Macro shapes
    private final SimplexNoise continentalNoise; 
    private final SimplexNoise macroTemperatureNoise;
    private final SimplexNoise macroMoistureNoise;

    // Medium/High Frequency - determines local shape
    private final SimplexNoise localHeightNoise;
    private final SimplexNoise localTempNoise;
    private final SimplexNoise localMoistNoise;

    private final SimplexNoise riverNoise;
    private final SimplexNoise mountainControlNoise; 
    private final SimplexNoise mountainNoise; // For actual mountains
    
    // Biomes
    private final IBiome standardBiome = new StandardBiome();
    private final IBiome desertBiome = new DesertBiome();

    // Scales
    private final double SCALE_CONTINENT = 0.0004;  // Continent Shape
    private final double SCALE_MACRO = 0.00008;      // Climate Zones (Very Large)
    private final double SCALE_LOCAL = 0.005;       // Local hills/bumps
    private final double SCALE_RIVER = 0.001;

    private static final double SEA_LEVEL = 0.42;

    public MapGenerator(long seed) {
        this.seed = seed;
        // Base structure
        this.continentalNoise = new SimplexNoise(seed);
        this.macroTemperatureNoise = new SimplexNoise(seed + 10);
        this.macroMoistureNoise = new SimplexNoise(seed + 20);
        
        // Local variations - Using seeds from previous version
        this.mountainNoise = new SimplexNoise(seed + 1);
        this.localHeightNoise = new SimplexNoise(seed + 2); // Was hillNoise
        this.localTempNoise = new SimplexNoise(seed + 40);
        this.localMoistNoise = new SimplexNoise(seed + 50);
        
        this.riverNoise = new SimplexNoise(seed + 5);
        this.mountainControlNoise = new SimplexNoise(seed + 7);
    }

    /**
     * Generate a single chunk with coherent terrain
     */
    public void generateChunk(Chunk chunk, ChunkManager chunkManager) {
        if (chunk.isGenerated()) return;

        for (int localX = 0; localX < Chunk.CHUNK_SIZE; localX++) {
            for (int localY = 0; localY < Chunk.CHUNK_SIZE; localY++) {
                int worldX = chunk.getWorldX(localX);
                int worldY = chunk.getWorldY(localY);
                
                // 1. Get Macro Climate & Continent Shape
                double continentVal = getContinentalValue(worldX, worldY); // 0.0 to 1.0 (approx)
                double macroTemp = getMacroTemperature(worldX, worldY);
                double macroMoist = getMacroMoisture(worldX, worldY);
                
                // 2. Determine Land vs Ocean
                boolean isLand = continentVal > SEA_LEVEL;
                
                // 3. Generate Height based on Biome Strategy
                double height;
                if (!isLand) {
                    // Ocean Generation
                    height = generateOceanHeight(continentVal, worldX, worldY);
                } else {
                    // Land Generation
                    // Normalize Land Factor (0.0 at coast -> 1.0 inland)
                    double landFactor = (continentVal - SEA_LEVEL) / (1.0 - SEA_LEVEL);
                    height = generateLandHeight(worldX, worldY, landFactor, macroTemp, macroMoist);
                }

                // 4. Resolve Final Environment (Temp/Moist)
                // We use the same Macro values but add tiny consistent noise
                double temperature = resolveTemperature(worldX, worldY, macroTemp, height);
                double moisture = resolveMoisture(worldX, worldY, macroMoist);

                // 5. River Logic (Continent Dependent)
                double riverFactor = getRiverFactor(worldX, worldY, height, macroMoist, temperature);
                boolean isRiver = riverFactor > 0;
                
                if (isRiver) {
                   double channelDepth = 0.06 * riverFactor;
                   height = height - channelDepth;
                   // Ensure rivers don't go below deep ocean
                   if (height < 0.2) height = 0.2; 
                }

                // 6. Set Tile
                TerrainType terrain = TerrainType.fromEnvironment(height, temperature, moisture, isRiver);

                Tile tile = new Tile(terrain, height);
                tile.setTemperature(temperature);
                tile.setMoisture(moisture);

                chunk.setTile(localX, localY, tile);
            }
        }
        chunk.setGenerated(true);
    }
    
    // ==========================================
    //              MACRO HELPERS
    // ==========================================

    private double getContinentalValue(int x, int y) {
        // Domain Warp for shapes
        double wx = x + continentalNoise.noise(x * 0.0001, y * 0.0001) * 200;
        double wy = y + continentalNoise.noise(y * 0.0001, x * 0.0001) * 200;
        
        return continentalNoise.octaveNoise(wx, wy, 4, 0.5, SCALE_CONTINENT);
    }
    
    private double getMacroTemperature(int x, int y) {
        // Very smooth, sweeping changes
        double val = macroTemperatureNoise.octaveNoise(x, y, 2, 0.5, SCALE_MACRO);
        // Returns ~0.0 to 1.0. 
        // 0.0 = Polar, 1.0 = Equatorial
        return val;
    }

    private double getMacroMoisture(int x, int y) {
        // Very smooth
        double val = macroMoistureNoise.octaveNoise(x, y, 2, 0.5, SCALE_MACRO);
        return val;
    }

    // ==========================================
    //              HEIGHT GENERATION
    // ==========================================

    private double generateOceanHeight(double continentVal, int x, int y) {
        // continentVal is < SEA_LEVEL (0.42)
        // range roughly 0.0 to 0.42
        
        // Normalize 0.0->0.42 to 0.0 -> 1.0
        double factor = continentVal / SEA_LEVEL;
        
        // Deep ocean (0.1) to Shallow coast (0.38)
        double base = 0.1 + (factor * 0.28); 
        
        // Small sand ripples
        double ripple = localHeightNoise.octaveNoise(x, y, 2, 0.5, 0.02) * 0.02;
        
        return Math.min(0.39, base + ripple);
    }

    private double generateLandHeight(int x, int y, double landFactor, double macroTemp, double macroMoist) {
        // Desert Identification
        // Desert if High Temp (>0.6) and Low Moist (<0.4)
        
        // Calculate "Desertness" 0.0 to 1.0
        // If Temp > 0.6 AND Moist < 0.4 -> We are in Desert Territory
        double desertScore = 0.0;
        
        if (macroTemp > 0.55 && macroMoist < 0.45) {
            // How deep in the desert are we?
            double dryFactor = (0.45 - macroMoist) / 0.45; // 0.0 at border, 1.0 at 0.0 moist
            double hotFactor = (macroTemp - 0.55) / 0.45;  // 0.0 at border, 1.0 at 1.0 temp
            desertScore = (dryFactor + hotFactor) / 2.0;
            // Boost score to make transitions sharpish but smooth
            desertScore = Math.min(1.0, desertScore * 1.5);
        }

        // Mountain Masks (Shared)
        // Use low freq noise to create "ranges" separated by flat gaps
        double rangeControl = mountainControlNoise.octaveNoise(x, y, 2, 0.5, 0.0003); 
        
        // Lowered threshold from 0.45 to 0.20 to allow more mountain ranges
        // Normalized to 0.0 - 1.0
        double mountainMask = Math.max(0.0, (rangeControl - 0.20) / 0.80);
        
        mountainMask = Math.min(mountainMask, landFactor * 5.0); // No mountains at beach
        // Removed pow(2.0) to make mountain ranges wider and less "peaky" at the base
        // kept linear so they transition slower
        
        // Base Land Height (Above water)
        double baseLand = SEA_LEVEL + 0.02 + (landFactor * 0.1); 

        // Generate biomes
        double hDesert = desertBiome.getHeight(x, y, baseLand, mountainMask, localHeightNoise, mountainNoise);
        double hStandard = standardBiome.getHeight(x, y, baseLand, mountainMask, localHeightNoise, mountainNoise);
        
        // Linear Interpolate based on Desertness
        return (hStandard * (1.0 - desertScore)) + (hDesert * desertScore);
    }

    // ==========================================
    //           ENVIRONMENT RESOLUTION
    // ==========================================

    private double resolveTemperature(int x, int y, double macroTemp, double height) {
        // Add minimal variation so we don't break continent logic
        double localVar = localTempNoise.noise(x * 0.01, y * 0.01) * 0.05;
        double base = macroTemp + localVar;
        
        // Height Cooling
        double heightCooling = Math.max(0, height - 0.5) * 0.4;
        
        return Math.max(0.0, Math.min(1.0, base - heightCooling));
    }

    private double resolveMoisture(int x, int y, double macroMoist) {
        // TIGHTLY follow macro moisture
        // This is key to preventing "Forests in Deserts"
        double localVar = localMoistNoise.noise(x * 0.01, y * 0.01) * 0.05;
        
        // If we are effectively in a desert macro-zone (<0.3), CLAMP the variation
        // so it never exceeds 0.45 (TerrainType threshold for non-Desert)
        if (macroMoist < 0.3) {
            // Allow variability but cap it at 0.40
            double result = macroMoist + localVar;
            if (result > 0.42) result = 0.42; 
            return Math.max(0.0, result);
        }
        
        return Math.max(0.0, Math.min(1.0, macroMoist + localVar));
    }

    private double getRiverFactor(int x, int y, double height, double macroMoist, double temperature) {
        // 1. Minimum Height (Ocean)
        if (height < SEA_LEVEL - 0.02) return 0.0;
        
        // 2. Frozen Rivers check (optional, but let's allow them for now)
        
        // 3. Aridity Check - Based on Continent Type
        // If we are in a dry continent, rivers drastically reduced
        double riverThreshold = 0.985;
        
        // If Moisture is low, raise threshold (harder to spawn)
        if (macroMoist < 0.35) {
            // Rapidly kill rivers in desert
            // 0.35 -> 0.985
            // 0.20 -> 1.2 (Impossible)
            double dryness = (0.35 - macroMoist) / 0.15; // 0..1
            riverThreshold += (dryness * 0.1); 
        }

        if (riverThreshold >= 1.0) return 0.0;
        
        double val = riverNoise.octaveNoise(x, y, 4, 0.5, SCALE_RIVER);
        double ridge = 1.0 - Math.abs(val - 0.5) * 2.0;
        
        if (ridge < riverThreshold) return 0.0;
        
        return (ridge - riverThreshold) / (1.0 - riverThreshold);
    }

    public long getSeed() {
        return seed;
    }
}

