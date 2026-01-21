package server;

import common.*;
import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles persistent storage of chunks to disk.
 * Uses a Region File format (similar to Anvil) to store 1024 chunks (32x32) per file.
 * This drastically reduces the number of files on disk.
 */
public class ChunkStorage {
    private final Path regionDirectory;
    private final Map<Long, RegionFile> regionCache = new HashMap<>(); // Synchronized access
    private final Object regionCacheLock = new Object();
    
    public ChunkStorage(String worldName) throws IOException {
        Path worldDirectory = Paths.get("worlds", worldName);
        this.regionDirectory = worldDirectory.resolve("regions");
        
        // Create directory
        Files.createDirectories(regionDirectory);
    }
    
    /**
     * Save a chunk to disk
     */
    public void saveChunk(Chunk chunk) throws IOException {
        if (!chunk.isGenerated()) return;
        
        int chunkX = chunk.getChunkX();
        int chunkY = chunk.getChunkY();
        
        // Calculate Region coordinates (32x32 chunks per region)
        int regionX = Math.floorDiv(chunkX, 32);
        int regionY = Math.floorDiv(chunkY, 32);
        
        // Local coordinates within region (0-31)
        int localX = Math.floorMod(chunkX, 32);
        int localY = Math.floorMod(chunkY, 32);
        
        byte[] data = chunk.serialize();
        
        RegionFile region = getRegionFile(regionX, regionY);
        synchronized (region) {
            region.writeChunk(localX, localY, data);
        }
    }
    
    /**
     * Load a chunk from disk, returns null if not found
     */
    public Chunk loadChunk(int chunkX, int chunkY) throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionY = Math.floorDiv(chunkY, 32);
        int localX = Math.floorMod(chunkX, 32);
        int localY = Math.floorMod(chunkY, 32);
        
        RegionFile region = getRegionFile(regionX, regionY);
        byte[] data;
        
        synchronized (region) {
            data = region.readChunk(localX, localY);
        }
        
        if (data == null) {
            return null;
        }
        
        return Chunk.deserialize(data);
    }
    
    /**
     * Get or open a region file
     */
    private RegionFile getRegionFile(int regionX, int regionY) throws IOException {
        long key = ((long)regionX << 32) | (regionY & 0xFFFFFFFFL);
        
        synchronized (regionCacheLock) {
            RegionFile file = regionCache.get(key);
            if (file == null) {
                // If cache is too big, close random one (simple LRU ideally, but for now just clear if massive)
                if (regionCache.size() > 50) {
                    closeAll();
                }
                
                Path path = regionDirectory.resolve("r." + regionX + "." + regionY + ".bin");
                file = new RegionFile(path);
                regionCache.put(key, file);
            }
            return file;
        }
    }
    
    public void closeAll() {
        synchronized (regionCacheLock) {
            for (RegionFile file : regionCache.values()) {
                try {
                    file.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            regionCache.clear();
        }
    }

    /**
     * Helper to close resources when server stops
     */
    public void close() {
        closeAll();
    }
    
    /**
     * Check if a chunk exists (roughly, by checking if it has non-zero offset in region)
     */
    public boolean chunkExists(int chunkX, int chunkY) {
        // This would require opening the region file to check the header. 
        // For performance, we might assume NO if not in cache? 
        // But for exactness we should check.
        try {
            int regionX = Math.floorDiv(chunkX, 32);
            int regionY = Math.floorDiv(chunkY, 32);
            int localX = Math.floorMod(chunkX, 32);
            int localY = Math.floorMod(chunkY, 32);
            
            RegionFile region = getRegionFile(regionX, regionY);
            synchronized (region) {
                return region.hasChunk(localX, localY);
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Save all chunks in the manager to disk
     */
    public void saveAllChunks(ChunkManager chunkManager) throws IOException {
        int saved = 0;
        for (Chunk chunk : chunkManager.getAllChunks()) {
            if (chunk != null && chunk.isGenerated()) {
                saveChunk(chunk);
                saved++;
            }
        }
        System.out.println("Saved " + saved + " chunks to disk using Region format.");
    }
    
    /**
     * Inner class handling the binary region file format
     * Format: 
     * Header: 4096 bytes (1024 ints)
     * - each int: location (offset: 24 bits, sector_count: 8 bits)
     * - offset is in 4KB sectors
     */
    private static class RegionFile {
        private static final int SECTOR_SIZE = 4096;
        private static final int CHUNKS_PER_REGION = 1024; // 32x32
        private final Path path;
        private RandomAccessFile file;
        private final int[] offsets; // Cache of the header
        
        public RegionFile(Path path) throws IOException {
            this.path = path;
            this.offsets = new int[CHUNKS_PER_REGION];
            
            boolean exists = Files.exists(path);
            this.file = new RandomAccessFile(path.toFile(), "rw");
            
            if (exists) {
                if (file.length() < SECTOR_SIZE) {
                    file.write(new byte[SECTOR_SIZE]); // Init header if broken
                }
                file.seek(0);
                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    offsets[i] = file.readInt();
                }
            } else {
                // New file, write empty header
                file.write(new byte[SECTOR_SIZE]);
            }
        }

        public boolean hasChunk(int x, int y) {
            return offsets[x + y * 32] != 0;
        }
        
        // Local coords 0-31
        public synchronized byte[] readChunk(int x, int y) throws IOException {
            int index = x + y * 32;
            int location = offsets[index];
            
            if (location == 0) return null;
            
            int offsetSectors = location >> 8;
            int lengthSectors = location & 0xFF;
            
            if (offsetSectors == 0) return null;
            
            file.seek((long) offsetSectors * SECTOR_SIZE);
            int preciseLength = file.readInt(); // format: [length:4][data:N][padding]
            
            if (preciseLength <= 0 || preciseLength > lengthSectors * SECTOR_SIZE) {
                return null; // Invalid
            }
            
            byte[] data = new byte[preciseLength];
            file.readFully(data);
            return data;
        }
        
        public synchronized void writeChunk(int x, int y, byte[] data) throws IOException {
            int index = x + y * 32;
            int oldLocation = offsets[index];
            
            // Total space needed: length integer + data
            int requiredSectors = (data.length + 4 + SECTOR_SIZE - 1) / SECTOR_SIZE;
            
            int newSectorOffset;
            
            // Can we overwrite existing?
            if (oldLocation != 0) {
                int oldOffset = oldLocation >> 8;
                int oldLength = oldLocation & 0xFF;
                
                if (requiredSectors <= oldLength) {
                    newSectorOffset = oldOffset;
                } else {
                    // Need new space -> Append to end (leak old space for now)
                    newSectorOffset = (int) (file.length() / SECTOR_SIZE);
                }
            } else {
                // New chunk -> Append
                newSectorOffset = (int) (file.length() / SECTOR_SIZE);
                // Ensure we don't write into header
                if (newSectorOffset == 0) newSectorOffset = 1; 
            }
            
            // Write data
            file.seek((long) newSectorOffset * SECTOR_SIZE);
            file.writeInt(data.length);
            file.write(data);
            
            // Update location
            int newLocation = (newSectorOffset << 8) | (requiredSectors & 0xFF);
            offsets[index] = newLocation;
            
            // Update header
            file.seek(index * 4);
            file.writeInt(newLocation);
        }
        
        public synchronized void close() throws IOException {
            if (file != null) file.close();
        }
    }
}
