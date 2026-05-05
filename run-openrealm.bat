@echo off
REM Native Java OpenRealm desktop client launcher (Windows).
REM Requires Java 17+. Pass the data-service host as the first argument; defaults
REM to 127.0.0.1.
REM
REM Usage:
REM   run-openrealm.bat
REM   run-openrealm.bat openrealm.net
REM   run-openrealm.bat openrealm.net email password characterUuid
set HOST=%1
if "%HOST%"=="" set HOST=127.0.0.1
shift
start java -jar .\target\openrealm-native-client.jar %HOST% %1 %2 %3
