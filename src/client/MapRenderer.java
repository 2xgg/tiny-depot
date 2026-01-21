package client;

import common.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders the tile map efficiently with view culling
 * Only renders visible tiles to maintain performance
 */
public class MapRenderer extends JPanel {
    private ChunkManager chunkManager;
    private NetworkClient networkClient;
    private int tileSize = 4; // Zoomed out by default to see more world (4 pixels per tile)
    private int viewX = 0; // Camera position
    private int viewY = 0;
    private int lastMouseX, lastMouseY;
    private boolean dragging = false;
    private AtomicInteger recentChunksReceived = new AtomicInteger(0);
    private volatile long lastReceptionTime = 0;

    public MapRenderer(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
        setPreferredSize(new Dimension(1200, 800));
        setBackground(Color.BLACK);
        setupMouseControls();
    }
    
    public void setNetworkClient(NetworkClient networkClient) {
        this.networkClient = networkClient;
    }

    private void setupMouseControls() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                dragging = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging) {
                    int dx = e.getX() - lastMouseX;
                    int dy = e.getY() - lastMouseY;
                    int divisor = Math.max(1, tileSize / 4);
                    viewX -= dx / divisor;
                    viewY -= dy / divisor;
                    lastMouseX = e.getX();
                    lastMouseY = e.getY();
                    repaint();
                }
            }
        });

        addMouseWheelListener(e -> {
            int notches = e.getWheelRotation();
            int oldTileSize = tileSize;
            if (notches < 0) {
                // Zoom in
                tileSize = Math.min(64, tileSize + 2);
            } else {
                // Zoom out - allow down to sub-pixel rendering
                if (tileSize > 4) {
                    tileSize = Math.max(1, tileSize - 2);
                } else {
                    tileSize = Math.max(1, tileSize - 1);
                }
            }
            repaint();
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        
        // Enable antialiasing for smoother rendering at small zoom levels
        if (tileSize < 8) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, 
                               RenderingHints.VALUE_ANTIALIAS_ON);
        }

        int width = getWidth();
        int height = getHeight();

        // Render tiles
        renderTiles(g2d);

        // Draw info overlay
        drawInfoOverlay(g2d);
    }

    /**
     * Standard tile-by-tile rendering
     */
    private void renderTiles(Graphics2D g) {
        int width = getWidth();
        int height = getHeight();

        // Calculate visible tile range
        int startTileX = viewX;
        int startTileY = viewY;
        int endTileX = viewX + (width / tileSize) + 2;
        int endTileY = viewY + (height / tileSize) + 2;

        // Calculate chunk range
        int startChunkX = Math.floorDiv(startTileX, Chunk.CHUNK_SIZE);
        int startChunkY = Math.floorDiv(startTileY, Chunk.CHUNK_SIZE);
        int endChunkX = Math.floorDiv(endTileX, Chunk.CHUNK_SIZE);
        int endChunkY = Math.floorDiv(endTileY, Chunk.CHUNK_SIZE);

        // Render visible chunks
        for (int chunkX = startChunkX; chunkX <= endChunkX; chunkX++) {
            for (int chunkY = startChunkY; chunkY <= endChunkY; chunkY++) {
                renderChunk(g, chunkX, chunkY);
            }
        }
    }

    private void renderChunk(Graphics2D g, int chunkX, int chunkY) {
        Chunk chunk = chunkManager.getChunk(chunkX, chunkY);
        
        if (!chunk.isGenerated()) {
            return; // Skip ungenerated chunks
        }

        // At very small zoom levels, sample fewer tiles for performance
        // This dramatically reduces draw calls from ~1M to ~60K at 1px zoom
        int sampleRate = 1;
        if (tileSize <= 1) {
            sampleRate = 4; // Render every 4th tile at 1px zoom
        } else if (tileSize == 2) {
            sampleRate = 2; // Render every 2nd tile at 2px zoom
        }

        for (int localX = 0; localX < Chunk.CHUNK_SIZE; localX += sampleRate) {
            for (int localY = 0; localY < Chunk.CHUNK_SIZE; localY += sampleRate) {
                Tile tile = chunk.getTile(localX, localY);
                if (tile == null) continue;

                int worldX = chunk.getWorldX(localX);
                int worldY = chunk.getWorldY(localY);
                
                int screenX = (worldX - viewX) * tileSize;
                int screenY = (worldY - viewY) * tileSize;

                // Skip if outside screen
                if (screenX < -tileSize || screenX > getWidth() || 
                    screenY < -tileSize || screenY > getHeight()) {
                    continue;
                }

                // At very small zoom levels, skip color variation for performance
                Color baseColor;
                if (tileSize >= 4) {
                    baseColor = tile.getTerrainType().getColorWithVariation(worldX, worldY);
                } else {
                    baseColor = tile.getTerrainType().getColor();
                }
                
                // Add terrain shading for depth (similar to unmined renderer)
                Color shadedColor = applyTerrainShading(baseColor, tile, chunkManager, worldX, worldY);
                g.setColor(shadedColor);
                
                // Draw larger rectangles when sampling to fill gaps
                int drawSize = Math.max(1, tileSize * sampleRate);
                g.fillRect(screenX, screenY, drawSize, drawSize);
            }
        }
    }
    
    /**
     * Apply terrain shading based on height differences to create depth effect
     * Uses normal mapping calculation for realistic 3D lighting
     */
    private Color applyTerrainShading(Color baseColor, Tile tile, ChunkManager chunkManager, int worldX, int worldY) {
        double centerHeight = tile.getHeight();
        boolean isWater = tile.getTerrainType() == TerrainType.RIVER || 
                          tile.getTerrainType() == TerrainType.OCEAN || 
                          tile.getTerrainType() == TerrainType.LAKE || 
                          tile.getTerrainType() == TerrainType.SHALLOW_WATER ||
                          tile.getTerrainType() == TerrainType.DEEP_OCEAN;

        // Modify water color based on depth (lower height = deeper = darker)
        if (isWater) {
             // Normalized depth: 0.0 (deepest) to 1.0 (surface)
             double seaLevel = 0.4;
             double depthFactor = Math.max(0.0, Math.min(1.0, centerHeight / seaLevel));
             
             // Tint towards deep blue
             int r = (int) (baseColor.getRed() * (0.6 + 0.4 * depthFactor));
             int g = (int) (baseColor.getGreen() * (0.6 + 0.4 * depthFactor));
             int b = (int) (baseColor.getBlue() * (0.7 + 0.3 * depthFactor));
             
             return new Color(r, g, b);
        }

        // Get neighbors for normal calculation
        double scale = 1.0; 
        double hW = getHeightAt(chunkManager, worldX - 1, worldY) * scale;
        double hE = getHeightAt(chunkManager, worldX + 1, worldY) * scale;
        double hN = getHeightAt(chunkManager, worldX, worldY - 1) * scale;
        double hS = getHeightAt(chunkManager, worldX, worldY + 1) * scale;
        
        // Calculate Normal Vector (central difference)
        double nx = hW - hE; 
        double ny = hN - hS; 
        double nz = 0.10; // Z component - Decrease to exaggerate slope (makes terrain look 3D)
        
        // Normalize vector
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        
        // Light Direction (North-West)
        double lx = -1.0;
        double ly = -1.0;
        double lz = 0.6; // Slightly higher sun to catch tops of ridges
        
        // Normalize light
        double lLen = Math.sqrt(lx*lx + ly*ly + lz*lz);
        lx /= lLen; ly /= lLen; lz /= lLen;
        
        // Dot Product (Diffuse Lighting)
        double dot = nx * lx + ny * ly + nz * lz;
        
        // Clamp dot for basic diffuse
        double diffuse = Math.max(0.0, dot);
        
        // Add ambient light
        double ambient = 0.3; // Darker shadows
        double lightIntensity = ambient + (diffuse * 1.0); // Stronger light
        
        // Extra highlight for peaks (Specular-ish)
        if (centerHeight > 0.6) {
           double spec = Math.pow(Math.max(0, dot), 10.0); // Shiny peaks
           lightIntensity += spec * 0.4;
        }

        int r = (int) Math.max(0, Math.min(255, baseColor.getRed() * lightIntensity));
        int g = (int) Math.max(0, Math.min(255, baseColor.getGreen() * lightIntensity));
        int b = (int) Math.max(0, Math.min(255, baseColor.getBlue() * lightIntensity));
        
        return new Color(r, g, b);
    }
    
    /**
     * Get height at world coordinates
     */
    private double getHeightAt(ChunkManager chunkManager, int worldX, int worldY) {
        Tile tile = chunkManager.getTile(worldX, worldY);
        return tile != null ? tile.getHeight() : 0.5;
    }

    private void drawInfoOverlay(Graphics2D g) {
        // Semi-transparent background for readability
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(5, 5, 450, 245);
        
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        
        int centerTileX = viewX + (getWidth() / tileSize) / 2;
        int centerTileY = viewY + (getHeight() / tileSize) / 2;
        int centerChunkX = Math.floorDiv(centerTileX, Chunk.CHUNK_SIZE);
        int centerChunkY = Math.floorDiv(centerTileY, Chunk.CHUNK_SIZE);
        
        Tile centerTile = chunkManager.getTile(centerTileX, centerTileY);
        String terrainInfo = centerTile != null ? 
            centerTile.getTerrainType().getDisplayName() : "Unknown";
        
        // Get height value for debugging
        String heightInfo = centerTile != null ? 
            String.format("%.3f", centerTile.getHeight()) : "N/A";
        
        // Get client memory information
        Runtime runtime = Runtime.getRuntime();
        long clientUsedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long clientMaxMemory = runtime.maxMemory() / (1024 * 1024);
        
        // Calculate chunk statistics
        int loadedChunks = chunkManager.getLoadedChunkCount();
        int totalTiles = loadedChunks * Chunk.CHUNK_SIZE * Chunk.CHUNK_SIZE;
        
        int y = 20;
        int lineHeight = 15;
        
        g.drawString("═══ WORLD INFO ═══", 10, y);
        y += lineHeight;
        g.drawString("Position: (" + centerTileX + ", " + centerTileY + ")", 10, y);
        y += lineHeight;
        g.drawString("Chunk: (" + centerChunkX + ", " + centerChunkY + ")", 10, y);
        y += lineHeight;
        g.drawString("Terrain: " + terrainInfo, 10, y);
        y += lineHeight;
        g.drawString("Height: " + heightInfo, 10, y);
        y += lineHeight + 3;
        
        g.drawString("═══ RENDERING ═══", 10, y);
        y += lineHeight;
        g.drawString("Zoom: " + tileSize + "px/tile", 10, y);
        y += lineHeight;
        g.drawString("Tiles Rendered: ~" + 
                     ((getWidth() / tileSize) * (getHeight() / tileSize)), 10, y);
        y += lineHeight + 3;
        
        g.drawString("═══ CLIENT ═══", 10, y);
        y += lineHeight;
        g.drawString("Chunks RAM: " + loadedChunks + " (" + totalTiles + " tiles)", 10, y);
        y += lineHeight;
        g.drawString("RAM Usage: " + clientUsedMemory + "MB / " + clientMaxMemory + "MB", 10, y);
        y += lineHeight + 3;
        
        // Show server stats if available
        if (networkClient != null && networkClient.isConnected()) {
            long serverUsedMB = networkClient.getServerUsedMemory() / (1024 * 1024);
            long serverTotalMB = networkClient.getServerTotalMemory() / (1024 * 1024);
            int serverThreads = networkClient.getServerThreadCount();
            int serverChunks = networkClient.getServerLoadedChunks();
            
            g.drawString("═══ SERVER ═══", 10, y);
            y += lineHeight;
            g.drawString("Chunks Loaded: " + serverChunks, 10, y);
            y += lineHeight;
            g.drawString("RAM Usage: " + serverUsedMB + "MB / " + serverTotalMB + "MB", 10, y);
            y += lineHeight;
            g.drawString("Active Threads: " + serverThreads, 10, y);
            y += lineHeight;
            g.setColor(Color.GREEN);
            g.drawString("● Connected", 10, y);
            g.setColor(Color.WHITE);
        } else {
            g.drawString("═══ SERVER ═══", 10, y);
            y += lineHeight;
            g.setColor(Color.RED);
            g.drawString("● Disconnected", 10, y);
            g.setColor(Color.WHITE);
        }
        y += lineHeight + 3;
        
        // Show recent chunk reception activity
        long currentTime = System.currentTimeMillis();
        long timeSinceReception = currentTime - lastReceptionTime;
        int receivedCount = recentChunksReceived.get();
        
        if (timeSinceReception < 1000 && receivedCount > 0) {
            g.setColor(Color.GREEN);
            g.drawString("⚡ Receiving: +" + receivedCount + " chunks/sec", 10, y);
        } else {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("✓ Idle", 10, y);
            recentChunksReceived.set(0); // Reset counter when idle
        }
    }

    public void setViewPosition(int x, int y) {
        this.viewX = x;
        this.viewY = y;
        repaint();
    }

    public void setChunkManager(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    public int getViewX() {
        return viewX;
    }

    public int getViewY() {
        return viewY;
    }

    public int getTileSize() {
        return tileSize;
    }

    public void onChunkReceived() {
        int count = recentChunksReceived.incrementAndGet();
        lastReceptionTime = System.currentTimeMillis();
        if (count % 50 == 0) {
            System.out.println("[RENDER] Chunk reception counter: " + count);
        }
    }
}
