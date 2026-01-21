# Setup Guide

## Prerequisites

- **Java JDK 21** (or later) installed
- Located at: `C:\Program Files\Java\jdk-21\`

## Quick Start

### Option 1: Using Batch Files (Easiest)

1. **Compile the project:**
   ```
   compile.bat
   ```

2. **Run the client:**
   ```
   run_client.bat
   ```

3. **Run the server** (optional, for multiplayer testing):
   ```
   run_server.bat
   ```

### Option 2: Manual Compilation

```bash
# Compile
"C:\Program Files\Java\jdk-21\bin\javac.exe" -d bin -sourcepath src src/common/*.java src/generation/*.java src/client/*.java src/server/*.java

# Run client
"C:\Program Files\Java\jdk-21\bin\java.exe" -cp bin client.GameClient

# Run server
"C:\Program Files\Java\jdk-21\bin\java.exe" -cp bin server.GameServer [port] [seed]
```

### Option 3: Add Java to PATH (Recommended)

To avoid using full paths:

1. Open **System Properties** → **Environment Variables**
2. Edit **Path** variable
3. Add: `C:\Program Files\Java\jdk-21\bin`
4. Restart your terminal
5. Then you can use simple commands:
   ```
   javac -d bin -sourcepath src src/**/*.java
   java -cp bin client.GameClient
   ```

## Client Controls

Once the client window opens:

- **Mouse Drag**: Pan the camera around the world
- **Mouse Wheel**: Zoom in/out
- **Generate Visible Area**: Force generation of chunks in your current view
- **Random Location**: Teleport to a random location on the map
- **Clear Chunks**: Unload all chunks from memory

## Troubleshooting

### "java is not recognized"
- Java is not in your PATH
- Use the batch files which have full paths hardcoded
- Or add Java to your PATH (see Option 3 above)

### Window opens but map is black
- Click "Generate Visible Area" button
- The map generates on-demand as you navigate

### Out of memory
- The default chunk limit is 1000 chunks
- Each chunk is 32×32 tiles (1024 tiles)
- Reduce the maxLoadedChunks in GameClient.java if needed

## Server Usage

```bash
# Start with defaults (port 25565, random seed)
run_server.bat

# Or specify port and seed
"C:\Program Files\Java\jdk-21\bin\java.exe" -cp bin server.GameServer 8080 12345
```

Server will:
- Generate spawn area (21×21 chunks around 0,0)
- Listen for client connections
- Serve chunks on demand

## What's Generated

On first run, the map generates procedurally:
- **Oceans and continents** using large-scale Simplex noise
- **Mountain ranges** with multiple octaves
- **Realistic terrain** from deep ocean → beach → grassland → mountains → snow peaks
- **Color-coded tiles** for easy visualization

The world is infinite - chunks generate as you explore!

## Next Steps

1. Explore the generated world by dragging and zooming
2. Try different seeds by modifying GameClient.java
3. Experiment with generation parameters in MapGenerator.java
4. Add your own terrain types in TerrainType.java

Enjoy building your game world! 🌍
