package generation;

import java.util.Random;

/**
 * Improved Perlin noise generator for smooth, natural-looking terrain
 * Uses gradient interpolation without diagonal artifacts
 */
public class SimplexNoise {
    private final int[] p = new int[512];
    
    // Gradient vectors for smooth interpolation
    private static final double[][] GRADIENTS = {
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    public SimplexNoise(long seed) {
        Random random = new Random(seed);
        int[] permutation = new int[256];
        
        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }
        
        // Shuffle using Fisher-Yates
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = permutation[i];
            permutation[i] = permutation[j];
            permutation[j] = temp;
        }
        
        // Duplicate for overflow
        for (int i = 0; i < 512; i++) {
            p[i] = permutation[i & 255];
        }
    }
    
    private static double fade(double t) {
        // 6t^5 - 15t^4 + 10t^3 - smoother than Perlin's original
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
    
    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
    
    private static double grad(int hash, double x, double y) {
        double[] g = GRADIENTS[hash & 7];
        return g[0] * x + g[1] * y;
    }
    
    /**
     * 2D Perlin noise
     * @param x X coordinate
     * @param y Y coordinate
     * @return Noise value between -1 and 1
     */
    public double noise(double x, double y) {
        // Find unit grid cell containing point
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        
        // Get relative coordinates within cell
        x -= Math.floor(x);
        y -= Math.floor(y);
        
        // Compute fade curves
        double u = fade(x);
        double v = fade(y);
        
        // Hash coordinates of the 4 square corners
        int a = p[X] + Y;
        int aa = p[a];
        int ab = p[a + 1];
        int b = p[X + 1] + Y;
        int ba = p[b];
        int bb = p[b + 1];
        
        // Blend results from 4 corners
        double result = lerp(v,
            lerp(u, grad(p[aa], x, y), grad(p[ba], x - 1, y)),
            lerp(u, grad(p[ab], x, y - 1), grad(p[bb], x - 1, y - 1))
        );
        
        return result;
    }

    /**
     * Octave noise - combines multiple frequencies for natural terrain
     * @param x X coordinate
     * @param y Y coordinate
     * @param octaves Number of noise layers
     * @param persistence How much each octave contributes
     * @param scale Overall scale of the noise
     * @return Noise value between 0 and 1
     */
    public double octaveNoise(double x, double y, int octaves, double persistence, double scale) {
        double total = 0;
        double frequency = scale;
        double amplitude = 1;
        double maxValue = 0;
        
        for (int i = 0; i < octaves; i++) {
            total += noise(x * frequency, y * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= 2;
        }
        
        return (total / maxValue + 1) / 2; // Normalize to 0-1
    }
}
