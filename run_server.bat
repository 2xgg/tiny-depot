@echo off
echo Starting Game Server...
echo Usage: run_server.bat [port] [worldName] [seed]
echo Defaults: port=25565, worldName=world, seed=random
echo.
"C:\Program Files\Java\jdk-21\bin\java.exe" -cp bin server.GameServer %*
pause
