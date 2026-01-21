package server;

import common.*;
import generation.MapGenerator;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Game server - manages world state and handles client connections.
 * 
 * <p><b>Protocol Version 1.0:</b>
 * The server communicates via a TCP socket using {@link DataInputStream} and {@link DataOutputStream}.
 * Commands are sent as UTF Strings followed by their arguments.
 * 
 * <ul>
 *   <li><b>Request:</b> {@code "GET_CHUNK" [int x] [int y]} <br>
 *       <b>Response:</b> {@code "CHUNK_DATA" [int length] [byte[] data]}
 *   </li>
 *   <li><b>Request:</b> {@code "GET_STATS"} <br>
 *       <b>Response:</b> {@code "STATS_DATA" [long usedMem] [long totalMem] [int threads] [int chunks]}
 *   </li>
 *   <li><b>Request:</b> {@code "DISCONNECT"} <br>
 *       <b>Action:</b> Closes connection.
 *   </li>
 * </ul>
 * 
 * All chunk generation and persistence happens server-side.
 */
public class GameServer {
    private final ServerConfig config;
    private final ChunkManager chunkManager;
    private final MapGenerator mapGenerator;
    private final ChunkStorage chunkStorage;
    private ServerSocket serverSocket;
    private final ExecutorService clientThreadPool;
    private final ScheduledExecutorService saveExecutor;
    private boolean running;

    public GameServer(ServerConfig config) {
        this.config = config;
        this.chunkManager = new ChunkManager(config.getServerMaxChunks());
        this.mapGenerator = new MapGenerator(config.getWorldSeed());
        this.clientThreadPool = Executors.newCachedThreadPool();
        this.saveExecutor = Executors.newScheduledThreadPool(1);
        this.running = false;
        
        // Initialize persistent storage
        try {
            this.chunkStorage = new ChunkStorage(config.getWorldName());
            System.out.println("Chunk storage initialized using Region File format.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize chunk storage", e);
        }
    }

    /**
     * Start the server
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(config.getPort());
        running = true;
        
        System.out.println("Server started on port " + config.getPort());
        System.out.println("World: " + config.getWorldName());
        System.out.println("Seed: " + config.getWorldSeed());
        
        // Pre-generate spawn area
        System.out.println("Generating spawn area...");
        generateSpawnArea();
        
        // Schedule periodic chunk saves
        int autosaveInterval = config.getAutosaveIntervalSeconds();
        saveExecutor.scheduleAtFixedRate(() -> {
            try {
                int saved = saveModifiedChunks();
                if (saved > 0) {
                    System.out.println("[AUTO-SAVE] Saved " + saved + " chunks");
                }
            } catch (Exception e) {
                System.err.println("Error during auto-save: " + e.getMessage());
            }
        }, autosaveInterval, autosaveInterval, TimeUnit.SECONDS);
        
        // Accept client connections
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                clientThreadPool.execute(new ClientHandler(clientSocket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting client: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Stop the server
     */
    public void stop() {
        running = false;
        
        System.out.println("Saving all chunks before shutdown...");
        try {
            chunkStorage.saveAllChunks(chunkManager);
        } catch (IOException e) {
            System.err.println("Error saving chunks: " + e.getMessage());
        }
        
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }
        
        saveExecutor.shutdown();
        clientThreadPool.shutdown();
    }

    /**
     * Generate chunks around spawn point (0, 0)
     */
    private void generateSpawnArea() {
        int radius = 10; // 10 chunks in each direction
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cy = -radius; cy <= radius; cy++) {
                Chunk chunk = chunkManager.getChunk(cx, cy);
                if (!chunk.isGenerated()) {
                    mapGenerator.generateChunk(chunk, chunkManager);
                }
            }
        }
        System.out.println("Spawn area generated (" + ((radius * 2 + 1) * (radius * 2 + 1)) + " chunks)");
    }
    
    /**
     * Save modified chunks to disk
     */
    private int saveModifiedChunks() throws IOException {
        int saved = 0;
        for (Chunk chunk : chunkManager.getAllChunks()) {
            if (chunk.isGenerated()) {
                chunkStorage.saveChunk(chunk);
                saved++;
            }
        }
        return saved;
    }

    /**
     * Get or generate a chunk - loads from disk if available, generates if not
     */
    public Chunk getChunk(int chunkX, int chunkY) {
        Chunk chunk = chunkManager.getChunk(chunkX, chunkY);
        if (!chunk.isGenerated()) {
            // Try to load from disk first
            try {
                Chunk loaded = chunkStorage.loadChunk(chunkX, chunkY);
                if (loaded != null) {
                    chunkManager.addChunk(loaded);
                    return loaded;
                }
            } catch (IOException e) {
                System.err.println("Error loading chunk (" + chunkX + ", " + chunkY + "): " + e.getMessage());
            }
            
            // Generate new chunk if not on disk
            mapGenerator.generateChunk(chunk, chunkManager);
            
            // Save newly generated chunk
            try {
                chunkStorage.saveChunk(chunk);
            } catch (IOException e) {
                System.err.println("Error saving chunk (" + chunkX + ", " + chunkY + "): " + e.getMessage());
            }
        }
        return chunk;
    }

    /**
     * Handle individual client connection
     */
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final ExecutorService chunkWorker;
        private long lastSecond = System.currentTimeMillis() / 1000;
        private int requestsThisSecond = 0;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.chunkWorker = Executors.newFixedThreadPool(4); // Process chunks in parallel
        }

        @Override
        public void run() {
            try (
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())
            ) {
                // Simple protocol - client requests chunks, server sends them
                while (socket.isConnected() && !socket.isClosed()) {
                    // Read chunk request
                    String command = in.readUTF();
                    
                    if (command.equals("LOGIN")) {
                        synchronized (out) {
                            out.writeUTF("LOGIN_OK");
                            out.writeLong(config.getWorldSeed());
                            out.flush();
                        }
                    } else if (command.equals("GET_CHUNK")) {
                        // Rate limiting check
                        long currentSecond = System.currentTimeMillis() / 1000;
                        if (currentSecond != lastSecond) {
                            lastSecond = currentSecond;
                            requestsThisSecond = 0;
                        }
                        
                        requestsThisSecond++;
                        if (requestsThisSecond > config.getMaxRequestsPerSecond()) {
                            // Client is requesting too fast, skip this request
                            System.err.println("[THROTTLE] Client " + socket.getInetAddress() + " exceeded rate limit");
                            continue;
                        }
                        
                        int chunkX = in.readInt();
                        int chunkY = in.readInt();
                        
                        // Validate chunk coordinates to prevent memory exhaustion
                        if (Math.abs(chunkX) > config.getMaxCoordinate() || Math.abs(chunkY) > config.getMaxCoordinate()) {
                            System.err.println("Client " + socket.getInetAddress() + " requested invalid chunk: (" + chunkX + ", " + chunkY + ")");
                            continue; // Ignore invalid requests
                        }
                        
                        // Check server memory before generating new chunks
                        Runtime runtime = Runtime.getRuntime();
                        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                        long maxMemory = runtime.maxMemory();
                        double memoryUsagePercent = (double) usedMemory / maxMemory;
                        
                        if (memoryUsagePercent > config.getEmergencyThreshold()) {
                            // Server is running out of memory, unload distant chunks
                            System.err.println("[WARNING] High memory usage (" + (int)(memoryUsagePercent * 100) + "%), performing emergency unload");
                            
                            // Unload chunks far from spawn
                            chunkManager.unloadDistantChunks(0, 0, 100);
                            
                            // Suggest garbage collection
                            System.gc();
                        }
                        
                        // Process chunk request asynchronously to prevent blocking
                        final int finalChunkX = chunkX;
                        final int finalChunkY = chunkY;
                        chunkWorker.execute(() -> {
                            try {
                                // Don't send if socket is closed
                                if (socket.isClosed()) {
                                    return;
                                }
                                
                                Chunk chunk = getChunk(finalChunkX, finalChunkY);
                                boolean isProcedural = !chunk.isModified();
                                
                                synchronized (out) {
                                    // Double-check socket before writing
                                    if (socket.isClosed()) {
                                        return;
                                    }
                                    
                                    if (isProcedural) {
                                        out.writeUTF("CHUNK_PROCEDURAL");
                                        out.writeInt(finalChunkX);
                                        out.writeInt(finalChunkY);
                                    } else {
                                        byte[] data = chunk.serialize();
                                        out.writeUTF("CHUNK_DATA");
                                        out.writeInt(data.length);
                                        out.write(data);
                                    }
                                    out.flush();
                                }
                            } catch (IOException e) {
                                System.err.println("Error sending chunk (" + finalChunkX + ", " + finalChunkY + "): " + e.getMessage());
                            }
                        });
                    } else if (command.equals("GET_STATS")) {
                        // Send server statistics
                        Runtime runtime = Runtime.getRuntime();
                        long totalMemory = runtime.totalMemory();
                        long freeMemory = runtime.freeMemory();
                        long usedMemory = totalMemory - freeMemory;
                        int threadCount = Thread.activeCount();
                        int loadedChunks = chunkManager.getLoadedChunkCount();
                        
                        synchronized (out) {
                            out.writeUTF("STATS_DATA");
                            out.writeLong(usedMemory);
                            out.writeLong(totalMemory);
                            out.writeInt(threadCount);
                            out.writeInt(loadedChunks);
                            out.flush();
                        }
                    } else if (command.equals("DISCONNECT")) {
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("Client disconnected: " + e.getMessage());
            } finally {
                chunkWorker.shutdown();
                try {
                    if (!chunkWorker.awaitTermination(10, TimeUnit.SECONDS)) {
                        chunkWorker.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    chunkWorker.shutdownNow();
                }
                
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Main entry point for server
     */
    public static void main(String[] args) {
        // Load server configuration
        ServerConfig config = new ServerConfig();
        String configFile = "server.properties";
        
        // Allow custom config file via command line
        if (args.length > 0) {
            configFile = args[0];
        }
        
        config.load(configFile);
        config.printConfig();

        GameServer server = new GameServer(config);
        
        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down server...");
            server.stop();
        }));
        
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
