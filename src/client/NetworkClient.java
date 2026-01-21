package client;

import common.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Network client that requests chunks from the game server.
 * 
 * <p>Implemented Protocol:
 * <ul>
 *   <li>{@code GET_CHUNK x y} -> Expects {@code CHUNK_DATA} response with GZIP compressed chunk bytes.</li>
 *   <li>{@code GET_STATS} -> Expects {@code STATS_DATA} with server memory/thread info.</li>
 * </ul>
 * 
 * Connects via TCP.
 */
public class NetworkClient {
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private boolean connected;
    private final ExecutorService requestExecutor;
    private final ScheduledExecutorService statsExecutor;
    private final ConcurrentHashMap<Long, CompletableFuture<Chunk>> pendingRequests;
    
    // Server stats
    private volatile long serverUsedMemory = 0;
    private volatile long serverTotalMemory = 0;
    private volatile int serverThreadCount = 0;
    private volatile int serverLoadedChunks = 0;
    private Runnable chunkReceivedCallback = null;
    
    // Local Generator for procedural chunks
    private generation.MapGenerator mapGenerator;

    public NetworkClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.connected = false;
        this.requestExecutor = Executors.newFixedThreadPool(8); // More threads for faster loading
        this.statsExecutor = Executors.newScheduledThreadPool(1);
        this.pendingRequests = new ConcurrentHashMap<>();
    }
    
    public void setMapGenerator(generation.MapGenerator mapGenerator) {
        this.mapGenerator = mapGenerator;
    }
    
    /**
     * Connect to the server and perform handshake.
     * @return The world seed if successful, or -1 if failed.
     */
    public long connect() {
        try {
            System.out.println("Connecting to " + host + ":" + port + "...");
            socket = new Socket(host, port);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            
            // Handshake
            output.writeUTF("LOGIN");
            output.flush();
            
            String response = input.readUTF();
            if (!"LOGIN_OK".equals(response)) {
                System.err.println("Handshake failed: Invalid response " + response);
                socket.close();
                return -1;
            }
            
            long seed = input.readLong();
            System.out.println("Handshake successful. Seed: " + seed);
            
            connected = true;
            
            // Start response listener thread
            Thread responseThread = new Thread(this::listenForResponses, "NetworkResponseListener");
            responseThread.setDaemon(true);
            responseThread.start();
            
            // Start periodic stats requests
            statsExecutor.scheduleAtFixedRate(this::requestStats, 1, 1, TimeUnit.SECONDS);
            
            System.out.println("Connected to server!");
            return seed;
        } catch (IOException e) {
            System.err.println("Failed to connect: " + e.getMessage());
            connected = false;
            return -1;
        }
    }
    
    /**
     * Disconnect from the server
     */
    public void disconnect() {
        connected = false;
        try {
            if (output != null) {
                output.writeUTF("DISCONNECT");
                output.flush();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        requestExecutor.shutdown();
        statsExecutor.shutdown();
    }
    
    /**
     * Request a chunk from the server (asynchronous)
     */
    public CompletableFuture<Chunk> requestChunk(int chunkX, int chunkY) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IOException("Not connected to server"));
        }
        
        long key = ((long)chunkX << 32) | (chunkY & 0xFFFFFFFFL);
        
        // Check if already requesting
        CompletableFuture<Chunk> existing = pendingRequests.get(key);
        if (existing != null) {
            // Check if the request is still pending (not done or timed out)
            if (!existing.isDone()) {
                return existing;
            }
            // Request completed (success or failure), allow new request
            pendingRequests.remove(key);
        }
        
        CompletableFuture<Chunk> future = new CompletableFuture<>();
        pendingRequests.put(key, future);
        
        // Send request on executor thread to avoid blocking
        requestExecutor.execute(() -> {
            try {
                synchronized (output) {
                    output.writeUTF("GET_CHUNK");
                    output.writeInt(chunkX);
                    output.writeInt(chunkY);
                    output.flush();
                }
            } catch (IOException e) {
                pendingRequests.remove(key);
                future.completeExceptionally(e);
            }
        });
        
        // Add timeout to prevent stuck requests (30 seconds)
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
            if (!future.isDone()) {
                pendingRequests.remove(key);
                future.completeExceptionally(new TimeoutException("Chunk request timed out: (" + chunkX + ", " + chunkY + ")"));
            }
        });
        
        return future;
    }
    
    /**
     * Listen for chunk responses from server
     */
    private void listenForResponses() {
        int chunksReceived = 0;
        try {
            while (connected && socket.isConnected()) {
                // Read message type
                String messageType = input.readUTF();
                
                if (messageType.equals("CHUNK_DATA")) {
                    // Read chunk data length
                    int dataLength = input.readInt();
                    byte[] data = new byte[dataLength];
                    input.readFully(data);
                    
                    // Deserialize chunk
                    Chunk chunk = Chunk.deserialize(data);
                    
                    completeChunkRequest(chunk);

                } else if (messageType.equals("CHUNK_PROCEDURAL")) {
                    int chunkX = input.readInt();
                    int chunkY = input.readInt();
                    
                    if (mapGenerator != null) {
                        // Offload procedural generation to executor to avoid blocking network thread
                        requestExecutor.execute(() -> {
                            // Generate locally
                            // Note: We need a dummy "ChunkManager" or refactor generateChunk to not need it 
                            // Current generateChunk needs ChunkManager to set "generated=true" technically?
                            // Checking signature: generateChunk(Chunk cx, ChunkManager cm)
                            // We can pass null if it's not strictly used, or a dummy.
                            // Looking at MapGenerator code, ChunkManager is UNUSED in logic.
                            
                            Chunk chunk = new Chunk(chunkX, chunkY);
                            mapGenerator.generateChunk(chunk, null);
                            chunk.setModified(false); // Valid procedural state
                            
                            completeChunkRequest(chunk);
                        });
                    } else {
                        System.err.println("Received CHUNK_PROCEDURAL but no MapGenerator set!");
                    }
                    
                } else if (messageType.equals("STATS_DATA")) {
                    // Read server stats
                    serverUsedMemory = input.readLong();
                    serverTotalMemory = input.readLong();
                    serverThreadCount = input.readInt();
                    serverLoadedChunks = input.readInt();
                }
            }
        } catch (IOException e) {
            if (connected) {
                System.err.println("Connection lost: " + e.getMessage());
                connected = false;
            }
        }
    }
    
    private void completeChunkRequest(Chunk chunk) {
        long key = ((long)chunk.getChunkX() << 32) | (chunk.getChunkY() & 0xFFFFFFFFL);
        CompletableFuture<Chunk> future = pendingRequests.remove(key);
        if (future != null) {
            future.complete(chunk);
            // Notify callback
            if (chunkReceivedCallback != null) {
                chunkReceivedCallback.run();
            }
        }
    }
    
    /**
     * Request server statistics
     */
    private void requestStats() {
        if (!connected) return;
        
        try {
            synchronized (output) {
                output.writeUTF("GET_STATS");
                output.flush();
            }
        } catch (IOException e) {
            // Connection might be lost
        }
    }
    
    /**
     * Get server statistics
     */
    public long getServerUsedMemory() { return serverUsedMemory; }
    public long getServerTotalMemory() { return serverTotalMemory; }
    public int getServerThreadCount() { return serverThreadCount; }
    public int getServerLoadedChunks() { return serverLoadedChunks; }
    public int getPendingRequestCount() { return pendingRequests.size(); }
    
    public void setChunkReceivedCallback(Runnable callback) {
        this.chunkReceivedCallback = callback;
    }
    
    /**
     * Check if connected to server
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }
}
