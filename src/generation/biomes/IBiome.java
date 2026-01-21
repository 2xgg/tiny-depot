package generation.biomes;

import generation.SimplexNoise;

/**
 * Interface for specific biome generation logic
 */
public interface IBiome {
    /**
     * Calculate terrain height for this biome
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param baseLandHeight The base height factor determined by continent generation (approx 0.42 to 1.0)
     * @param mountainMask Control for where mountains differ
     * @param hillNoise Access to noise generators
     * @param mountainNoise Access to noise generators
     * @return Final height (0.0 to 1.0)
     */
    double getHeight(int worldX, int worldY, double baseLandHeight, double mountainMask, SimplexNoise hillNoise, SimplexNoise mountainNoise);

    /**
     * Whether this biome allows rivers
     */
    boolean allowRivers();
}