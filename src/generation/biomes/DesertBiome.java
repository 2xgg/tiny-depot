package generation.biomes;

import generation.SimplexNoise;

/**
 * Desert biome - flat plateaus, almost no hills, no mountains
 */
public class DesertBiome implements IBiome {
    @Override
    public double getHeight(int worldX, int worldY, double baseLandHeight, double mountainMask, SimplexNoise hillNoise, SimplexNoise mountainNoise) {
        // Flatten base to a consistent plateau
        // We reduce the influence of the "continental" slope so it stays relatively flat
        // baseLandHeight usually varies from 0.42 to ~0.50
        
        // Add very subtle dune noise
        double dunes = hillNoise.octaveNoise(worldX, worldY, 2, 0.5, 0.02);
        dunes = (dunes - 0.5) * 0.02; // Very small variation
        
        // Rocky Mountains in Desert
        // We use the same mountain noise as standard biome to ensure ranges connect
        double rawM = mountainNoise.octaveNoise(worldX, worldY, 5, 0.5, 0.002);
        double mountains = 1.0 - (Math.abs(rawM - 0.5) * 2.0);
        mountains = Math.pow(mountains, 3.0); // Match standard biome shape
        
        // Return mostly flat terrain with occasional sharp mountains
        return baseLandHeight + dunes + (mountains * 0.48 * mountainMask);
    }

    @Override
    public boolean allowRivers() {
        return false;
    }
}