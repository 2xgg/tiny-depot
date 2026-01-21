package common;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Represents a chunk of the world map. Chunks are fixed size sections
 * of the world that can be loaded/unloaded as needed for efficient memory usage.
 */
public class Chunk {
    // Removed Serializable interface and serialVersionUID as we use custom binary format
    
    public static final int CHUNK_SIZE = 16; // 16x16 tiles per chunk as requested
    
    private final int chunkX; // Chunk coordinates
    private final int chunkY;
    private final Tile[][] tiles;
    private boolean generated;
    private boolean modified; // True if player has altered the chunk from its procedural state
    
    public Chunk(int chunkX, int chunkY) {
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.tiles = new Tile[CHUNK_SIZE][CHUNK_SIZE];
        this.generated = false;
        this.modified = false;
    }

    public int getChunkX() {
        return chunkX;
    }
    
    public boolean isModified() {
        return modified;
    }
    
    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public int getChunkY() {
        return chunkY;
    }

    public Tile getTile(int localX, int localY) {
        if (localX < 0 || localX >= CHUNK_SIZE || localY < 0 || localY >= CHUNK_SIZE) {
            return null;
        }
        return tiles[localX][localY];
    }

    public void setTile(int localX, int localY, Tile tile) {
        if (localX >= 0 && localX < CHUNK_SIZE && localY >= 0 && localY < CHUNK_SIZE) {
            tiles[localX][localY] = tile;
        }
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    /**
     * Returns world X coordinate from local tile coordinate
     */
    public int getWorldX(int localX) {
        return chunkX * CHUNK_SIZE + localX;
    }

    /**
     * Returns world Y coordinate from local tile coordinate
     */
    public int getWorldY(int localY) {
        return chunkY * CHUNK_SIZE + localY;
    }

    /**
     * Serialize chunk to byte array using efficient GZIP compression
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             DataOutputStream out = new DataOutputStream(gzip)) {
            
            out.writeInt(chunkX);
            out.writeInt(chunkY);
            out.writeBoolean(generated);
            out.writeBoolean(modified);
            
            // Serialize tiles row by row
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int y = 0; y < CHUNK_SIZE; y++) {
                    Tile tile = tiles[x][y];
                    if (tile != null) {
                        out.writeBoolean(true); // Tile exists
                        // Write small ordinal instead of full object
                        out.writeByte(tile.getTerrainType().ordinal()); 
                        // Use floats to save space (half the size of double)
                        out.writeFloat((float) tile.getHeight());
                        out.writeFloat((float) tile.getTemperature());
                        out.writeFloat((float) tile.getMoisture());
                        
                        // MMORPG Fields
                        out.writeLong(tile.getOwnerId());
                        out.writeInt(tile.getStructureId());
                        out.writeInt(tile.getContentAmount());
                        out.writeByte(tile.getRotation());
                    } else {
                        out.writeBoolean(false); // Null tile
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    /**
     * Deserialize chunk from compressed byte array
     */
    public static Chunk deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try (GZIPInputStream gzip = new GZIPInputStream(bais);
             DataInputStream in = new DataInputStream(gzip)) {
            
            int chunkX = in.readInt();
            int chunkY = in.readInt();
            Chunk chunk = new Chunk(chunkX, chunkY);
            chunk.setGenerated(in.readBoolean());
            chunk.setModified(in.readBoolean());
            
            TerrainType[] terrainTypes = TerrainType.values();
            
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int y = 0; y < CHUNK_SIZE; y++) {
                    boolean exists = in.readBoolean();
                    if (exists) {
                        int ordinal = in.readByte() & 0xFF; // Unsigned byte
                        double height = in.readFloat();
                        double temp = in.readFloat();
                        double moisture = in.readFloat();
                        
                        // MMORPG Fields
                        // Check availability or default if reading old format? Not easy with raw stream.
                        // We will assume new chunks only for now, or catch EOF.
                        // But DataInputStream EOFException is messy. 
                        // WARNING: This breaks compatibility with existing saves.
                        long ownerId = in.readLong();
                        int structureId = in.readInt();
                        int contentAmount = in.readInt();
                        byte rotation = in.readByte();
                        
                        // Safety check for ordinal
                        TerrainType type = TerrainType.OCEAN;
                        if (ordinal >= 0 && ordinal < terrainTypes.length) {
                            type = terrainTypes[ordinal];
                        }
                        
                        Tile tile = new Tile(type, height);
                        tile.setTemperature(temp);
                        tile.setMoisture(moisture);
                        
                        // Set new fields
                        tile.setOwnerId(ownerId);
                        tile.setStructureId(structureId);
                        tile.setContentAmount(contentAmount);
                        tile.setRotation(rotation);
                        
                        chunk.setTile(x, y, tile);
                    }
                }
            }
            return chunk;
        }
    }
}
