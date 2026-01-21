@echo off
echo Starting Game Client...
echo Usage: run_client.bat [host] [port]
echo Defaults: host=localhost, port=25565
echo.
"C:\Program Files\Java\jdk-21\bin\java.exe" -cp bin client.GameClient %*
pause
