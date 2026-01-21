# 2D World Map Generator

A high-performance, procedural 2D world map generator featuring infinite terrain, realistic biomes, and a complete client-server architecture.

## Core Architecture

### 1. Generation System (`src/generation`)
The generation system uses a multi-stage noise pipeline to create realistic terrain:
- **Simplex Noise:** Uses 7 distinct noise layers (Continental, Mountain, Hill, Temperature, Moisture, River).
- **Domain Warping:** Distorts noise coordinates to create organic, non-linear shapes.
- **Biomes:** Determines terrain type based on Height (Elevation), Temperature (Latitude/Noise), and Moisture (Rainfall).
- **Rivers:** Uses ridged multi-fractal noise to create continuous river networks that cut through continents.

### 2. Common Data Structures (`src/common`)
- **Chunk:** Represents a 16x16 area of the world.
- **Tile:** A single pixel/cell containing height, temperature, moisture, and terrain type.
- **TerrainType:** Enum defining all biomes (Forest, Desert, Ocean, etc.) and their color properties.

### 3. Server (`src/server`)
- **GameServer:** Main entry point. Manages the thread pool and client connections.
- **ChunkManager:** Handles caching, generation requests, and memory management.
- **ChunkStorage:** Saves/Loads chunks to disk (`cache/` directory) to persist the world.
- **Protocol:** Custom binary protocol for efficient chunk transmission.

### 4. Client (`src/client`)
- **GameClient:** Main entry point. Initializes the window and connections.
- **MapRenderer:** customized `JPanel` that renders visible tiles.
  - **Culling:** Only draws tiles inside the camera view.
  - **Shading:** Applies pseudo-3D lighting (Slope + Ambient Occlusion) to create depth.
- **NetworkClient:** Asynchronously requests chunks from the server.

## Technical Details

### World Scale

- **Target size**: 21600x10800 tiles
- **Chunk size**: 16x16 tiles
- **Total chunks** (at max size): 675 × 337.5 ≈ 228,000 chunks
- **Infinite generation**: Can generate beyond preset bounds

### Terrain Generation

The map generator uses multiple layers of noise with advanced post-processing:

1. **Continentalness** (C): Large landmasses and oceans (scale ~0.00008-0.002)
2. **Erosion** (E): Regional elevation smoothing (scale ~0.002-0.01)
3. **Weirdness** (W): Detail variation with domain warping (scale ~0.01-0.05)
4. **Peaks/Valleys (PV)**: `1 - |3*|W| - 2|` amplified for dramatic mountains
5. **Temperature & Moisture**: Biome modifiers
6. **River Noise**: Seed detection for waterway placement

**Post-processing steps:**
- Height quantization (40 levels) for blocky appearance
- Smoothing passes to reduce artifacts
- River carving with downhill routing
- Cross-chunk stitching for seamless terrain
- Water height clamping and cleanup

Height values (0.0-1.0) map to terrain types:
- 0.0-0.2: Deep Ocean (dark blue)
- 0.2-0.35: Ocean (blue)
- 0.35-0.4: Shallow Water (light blue)
- 0.4-0.45: Beach (sand)
- 0.45-0.6: Grassland (green)
- 0.6-0.7: Forest (dark green)
- 0.7-0.8: Hills (yellow-brown)
- 0.8-0.9: Mountain (grey)
- 0.9-1.0: Snow Peak (white)
- Rivers: Carved waterways connecting to oceans

### 2.5D Shading Algorithm

Terrain rendering uses a custom-built directional shading system:

- **Directional Lighting**: Light from northwest, slope-based shadows
- **Ambient Occlusion**: Valleys darken (negative correlation with height)
- **Slope Emphasis**: Steeper areas get stronger shading
- **Height Tinting**: Mountain peaks brighten (land only)
- **Water Special Handling**: Gentler lighting, clamped shading range

### Memory Management

- **Client**: Configurable chunk limit (default 1000 chunks = ~1MB)
- **Server**: Higher limit (default 10000 chunks = ~10MB)
- Automatic unloading of distant chunks
- Lazy generation - chunks generated only when needed
- Chunk modification flags for efficient server broadcasting

## Project Structure

```
src/
├── common/              # Shared code between client and server
│   ├── TerrainType.java    # Terrain definitions and colors
│   ├── Tile.java           # Individual tile data
│   ├── Chunk.java          # 16x16 tile container
│   └── ChunkManager.java   # Chunk memory management
│
├── generation/          # Map generation algorithms
│   ├── SimplexNoise.java        # Simplex noise implementation
│   └── MapGenerator.java        # Main generation coordinator
│
├── client/              # Client-side rendering
│   ├── MapRenderer.java    # Tile rendering with 2.5D shading
│   └── GameClient.java     # Main client application
│
├── server/              # Server-side world management
│   └── GameServer.java     # Network server and world state
│
└── tools/               # Development utilities
    └── WorldImageExporter.java  # PNG preview generator with diagnostics
```
