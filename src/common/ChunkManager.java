package common;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chunks in memory with LRU-style eviction for large worlds
 */
public class ChunkManager {
    private final Map<Long, Chunk> loadedChunks;
    private final int maxLoadedChunks;
    
    public ChunkManager(int maxLoadedChunks) {
        this.loadedChunks = new ConcurrentHashMap<>();
        this.maxLoadedChunks = maxLoadedChunks;
    }

    /**
     * Converts chunk coordinates to a unique long key
     */
    private long getChunkKey(int chunkX, int chunkY) {
        return ((long) chunkX << 32) | (chunkY & 0xFFFFFFFFL);
    }

    /**
     * Gets or creates a chunk at the specified chunk coordinates
     */
    public Chunk getChunk(int chunkX, int chunkY) {
        long key = getChunkKey(chunkX, chunkY);
        return loadedChunks.computeIfAbsent(key, k -> new Chunk(chunkX, chunkY));
    }

    /**
     * Checks if a chunk is loaded
     */
    public boolean isChunkLoaded(int chunkX, int chunkY) {
        return loadedChunks.containsKey(getChunkKey(chunkX, chunkY));
    }

    /**
     * Unloads a chunk from memory
     */
    public void unloadChunk(int chunkX, int chunkY) {
        loadedChunks.remove(getChunkKey(chunkX, chunkY));
    }

    /**
     * Gets tile at world coordinates
     */
    public Tile getTile(int worldX, int worldY) {
        int chunkX = Math.floorDiv(worldX, Chunk.CHUNK_SIZE);
        int chunkY = Math.floorDiv(worldY, Chunk.CHUNK_SIZE);
        int localX = worldX - (chunkX * Chunk.CHUNK_SIZE);
        int localY = worldY - (chunkY * Chunk.CHUNK_SIZE);
        
        Chunk chunk = getChunk(chunkX, chunkY);
        return chunk.getTile(localX, localY);
    }

    /**
     * Sets tile at world coordinates
     */
    public void setTile(int worldX, int worldY, Tile tile) {
        int chunkX = Math.floorDiv(worldX, Chunk.CHUNK_SIZE);
        int chunkY = Math.floorDiv(worldY, Chunk.CHUNK_SIZE);
        int localX = worldX - (chunkX * Chunk.CHUNK_SIZE);
        int localY = worldY - (chunkY * Chunk.CHUNK_SIZE);
        
        Chunk chunk = getChunk(chunkX, chunkY);
        chunk.setTile(localX, localY, tile);
    }

    /**
     * Unloads distant chunks to maintain memory limits
     */
    public void unloadDistantChunks(int centerChunkX, int centerChunkY, int keepRadius) {
        loadedChunks.entrySet().removeIf(entry -> {
            Chunk chunk = entry.getValue();
            int dx = Math.abs(chunk.getChunkX() - centerChunkX);
            int dy = Math.abs(chunk.getChunkY() - centerChunkY);
            return dx > keepRadius || dy > keepRadius;
        });
    }

    /**
     * Returns the number of currently loaded chunks
     */
    public int getLoadedChunkCount() {
        return loadedChunks.size();
    }

    /**
     * Clears all loaded chunks
     */
    public void clear() {
        loadedChunks.clear();
    }
    
    /**
     * Gets all loaded chunks (for saving to disk)
     */
    public java.util.Collection<Chunk> getAllChunks() {
        return loadedChunks.values();
    }
    
    /**
     * Adds a pre-loaded chunk to the manager
     */
    public void addChunk(Chunk chunk) {
        long key = getChunkKey(chunk.getChunkX(), chunk.getChunkY());
        loadedChunks.put(key, chunk);
    }
}
