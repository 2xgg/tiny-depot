package generation.biomes;

import generation.SimplexNoise;

/**
 * Standard biome with rolling hills and mountains
 * Used for Forests, Grasslands, Tundra, etc.
 */
public class StandardBiome implements IBiome {
    @Override
    public double getHeight(int worldX, int worldY, double baseLandHeight, double mountainMask, SimplexNoise hillNoise, SimplexNoise mountainNoise) {
        // Hills (Smoother rolling hills)
        double hills = hillNoise.octaveNoise(worldX, worldY, 4, 0.5, 0.01);
        hills = (hills - 0.5) * 2.0; // -1 to 1 variant
        
        // Mountains (Ridged Noise for distinct mountain ranges)
        double rawM = mountainNoise.octaveNoise(worldX, worldY, 5, 0.5, 0.002);
        
        // Create ridges: 0.5 is high, 0/1 are low
        double mountains = 1.0 - (Math.abs(rawM - 0.5) * 2.0);
        
        // Sharpen ridges, but less aggressively than before (6.0 -> 3.0)
        // This makes mountains wider and more visible
        mountains = Math.pow(mountains, 3.0); 
        
        // Base land
        double height = baseLandHeight;
        
        // Add Hills
        height += (hills * 0.05);
        
        // Add Mountains
        // Increased height contribution slightly (0.44 -> 0.48)
        height += (mountains * 0.48 * mountainMask);
        
        return height;
    }

    @Override
    public boolean allowRivers() {
        return true;
    }
}