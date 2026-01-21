package client;

import common.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Main client application - displays and interacts with the world map
 * Requests chunks from server instead of generating locally
 */
public class GameClient {
    private JFrame frame;
    private MapRenderer mapRenderer;
    private ChunkManager chunkManager;
    private NetworkClient networkClient;
    private Thread chunkRequestThread;
    private volatile boolean running = true;
    private volatile int cachedViewX = Integer.MIN_VALUE;
    private volatile int cachedViewY = Integer.MIN_VALUE;
    private volatile int cachedTileSize = -1;

    public GameClient(String serverHost, int serverPort) {
        // Initialize systems
        chunkManager = new ChunkManager(5000); // Client can cache more chunks
        networkClient = new NetworkClient(serverHost, serverPort);
        
        // Connect to server
        long seed = networkClient.connect();
        if (seed == -1) {
            JOptionPane.showMessageDialog(null, 
                "Failed to connect to server at " + serverHost + ":" + serverPort, 
                "Connection Error", 
                JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        
        // Initialize local generator for procedural chunks
        System.out.println("Initializing local generator with seed: " + seed);
        generation.MapGenerator mapGenerator = new generation.MapGenerator(seed);
        networkClient.setMapGenerator(mapGenerator);
        
        // Setup UI
        setupUI();
        
        // Pass network client and cache to renderer for stats display
        mapRenderer.setNetworkClient(networkClient);
        
        // Set up callback to track chunk reception activity
        networkClient.setChunkReceivedCallback(() -> {
            mapRenderer.onChunkReceived();
        });
        
        // Start background chunk requesting
        startChunkRequesting();
        
        // Start repaint timer for smooth updates
        Timer repaintTimer = new Timer(100, e -> mapRenderer.repaint());
        repaintTimer.start();
    }

    private void setupUI() {
        frame = new JFrame("2D World Map Generator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        mapRenderer = new MapRenderer(chunkManager);
        
        JPanel controlPanel = createControlPanel();
        
        frame.setLayout(new BorderLayout());
        frame.add(mapRenderer, BorderLayout.CENTER);
        frame.add(controlPanel, BorderLayout.SOUTH);
        
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel();
        
        // Removed "Generate Visible Area" button - generation is automatic now
        
        JButton clearButton = new JButton("Clear Chunks");
        clearButton.addActionListener(e -> {
            chunkManager.clear();
            mapRenderer.repaint();
        });
        
        JButton teleportButton = new JButton("Random Location");
        teleportButton.addActionListener(e -> {
            int randomX = (int) (Math.random() * 10000) - 5000;
            int randomY = (int) (Math.random() * 5000) - 2500;
            mapRenderer.setViewPosition(randomX, randomY);
        });
        
        JButton screenshotButton = new JButton("Screenshot");
        screenshotButton.addActionListener(e -> takeScreenshot());
        
        panel.add(clearButton);
        panel.add(teleportButton);
        panel.add(screenshotButton);
        
        return panel;
    }

    private void startChunkRequesting() {
        chunkRequestThread = new Thread(() -> {
            while (running) {
                try {
                    // Request visible chunks from server
                    requestVisibleChunks();
                    Thread.sleep(50); // Reduced check rate (20 TPS) to save CPU
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("Request error: " + e.getMessage());
                }
            }
        }, "ChunkRequester");
        chunkRequestThread.setDaemon(true);
        chunkRequestThread.setPriority(Thread.NORM_PRIORITY - 1);
        chunkRequestThread.start();
    }

    private void requestVisibleChunks() {
        if (!networkClient.isConnected()) {
            return;
        }
        
        // Get current view state
        int currentViewX = mapRenderer.getViewX();
        int currentViewY = mapRenderer.getViewY();
        int currentTileSize = Math.max(1, mapRenderer.getTileSize());
        
        // Calculate visible chunk range
        int visibleTilesX = (mapRenderer.getWidth() / currentTileSize) + 2;
        int visibleTilesY = (mapRenderer.getHeight() / currentTileSize) + 2;
        
        int startChunkX = Math.floorDiv(currentViewX, Chunk.CHUNK_SIZE) - 1;
        int startChunkY = Math.floorDiv(currentViewY, Chunk.CHUNK_SIZE) - 1;
        int endChunkX = Math.floorDiv(currentViewX + visibleTilesX, Chunk.CHUNK_SIZE) + 1;
        int endChunkY = Math.floorDiv(currentViewY + visibleTilesY, Chunk.CHUNK_SIZE) + 1;
        
        // Calculate center chunk for prioritization
        int centerChunkX = (startChunkX + endChunkX) / 2;
        int centerChunkY = (startChunkY + endChunkY) / 2;
        
        // Build list of chunks that need loading, sorted by distance from center
        java.util.List<ChunkRequest> chunksToLoad = new java.util.ArrayList<>();
        
        int loadedFromCache = 0;
        int maxCacheLoads = 50; // Limit disk I/O per cycle

        for (int chunkX = startChunkX; chunkX <= endChunkX; chunkX++) {
            for (int chunkY = startChunkY; chunkY <= endChunkY; chunkY++) {
                Chunk chunk = chunkManager.getChunk(chunkX, chunkY);
                
                if (!chunk.isGenerated()) {
                    // Calculate distance from center
                    int dx = chunkX - centerChunkX;
                    int dy = chunkY - centerChunkY;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    chunksToLoad.add(new ChunkRequest(chunkX, chunkY, distance));
                }
            }
        }
        
        // Sort by distance (closest first)
        chunksToLoad.sort((a, b) -> Double.compare(a.distance, b.distance));
        
        // Request missing chunks in priority order
        int requested = 0;
        int maxRequestsPerCycle = 50; // Balanced limit
        
        for (ChunkRequest req : chunksToLoad) {
            if (requested >= maxRequestsPerCycle) break;
            
            // Request from server
            final int cx = req.x;
            final int cy = req.y;
            networkClient.requestChunk(cx, cy).thenAccept(loadedChunk -> {
                chunkManager.addChunk(loadedChunk);
            }).exceptionally(ex -> {
                System.err.println("Failed to load chunk (" + cx + ", " + cy + "): " + ex.getMessage());
                return null;
            });
            requested++;
        }
        
        if (requested > 0) {
            // Only log if something happened
            // System.out.println("[NET] Requested " + requested);
        }
        
        // Unload chunks that are far from view (centerChunkX/Y already calculated above)
        int keepRadius = Math.max(50, Math.max(endChunkX - startChunkX, endChunkY - startChunkY) * 2);
        
        int beforeUnload = chunkManager.getLoadedChunkCount();
        chunkManager.unloadDistantChunks(centerChunkX, centerChunkY, keepRadius);
        int afterUnload = chunkManager.getLoadedChunkCount();
        
        if (beforeUnload - afterUnload > 0) {
            System.out.println("[MEM] Unloaded " + (beforeUnload - afterUnload) + " chunks | Remaining: " + afterUnload);
        }
    }

    /**
     * Helper class for prioritizing chunk requests by distance
     */
    private static class ChunkRequest {
        final int x, y;
        final double distance;
        
        ChunkRequest(int x, int y, double distance) {
            this.x = x;
            this.y = y;
            this.distance = distance;
        }
    }
    
    /**
     * Get the network client for accessing server stats
     */
    public NetworkClient getNetworkClient() {
        return networkClient;
    }
    
    /**
     * Take a screenshot of the current map view and save it to disk
     */
    private void takeScreenshot() {
        try {
            // Create screenshots directory if it doesn't exist
            File screenshotsDir = new File("screenshots");
            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs();
            }
            
            // Create Robot for screen capture
            Robot robot = new Robot();
            
            // Get the map renderer bounds on screen
            Rectangle bounds = mapRenderer.getBounds();
            Point location = mapRenderer.getLocationOnScreen();
            Rectangle captureRect = new Rectangle(location.x, location.y, bounds.width, bounds.height);
            
            // Capture the screenshot
            BufferedImage screenshot = robot.createScreenCapture(captureRect);
            
            // Generate filename with timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            String filename = "screenshots/screenshot_" + sdf.format(new Date()) + ".png";
            
            // Save the image
            javax.imageio.ImageIO.write(screenshot, "png", new File(filename));
            
            System.out.println("Screenshot saved: " + filename);
            
            // Show confirmation dialog
            JOptionPane.showMessageDialog(frame, 
                "Screenshot saved as: " + filename, 
                "Screenshot Taken", 
                JOptionPane.INFORMATION_MESSAGE);
                
        } catch (Exception e) {
            System.err.println("Failed to take screenshot: " + e.getMessage());
            JOptionPane.showMessageDialog(frame, 
                "Failed to take screenshot: " + e.getMessage(), 
                "Screenshot Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    public static void main(String[] args) {
        String host = "localhost";
        int port = 25565;
        
        if (args.length > 0) {
            host = args[0];
        }
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port, using default: " + port);
            }
        }
        
        final String serverHost = host;
        final int serverPort = port;
        
        SwingUtilities.invokeLater(() -> {
            new GameClient(serverHost, serverPort);
        });
    }
}
