@echo off
echo Compiling 2D World Map Generator...

REM Create bin directory if it doesn't exist
if not exist "bin" mkdir bin

REM Compile all Java files
"C:\Program Files\Java\jdk-21\bin\javac.exe" -d bin -sourcepath src src/common/*.java src/generation/*.java src/client/*.java src/server/*.java

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Compilation successful!
    echo.
    echo To run the client: java -cp bin client.GameClient
    echo To run the server: java -cp bin server.GameServer
) else (
    echo.
    echo Compilation failed!
)

pause
