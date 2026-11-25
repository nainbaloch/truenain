@echo off
setlocal
cd /d "%~dp0"
if exist "gradle\gradle-daemon-jvm.properties" (
  echo ERROR: Remove project-specific Gradle daemon JVM criteria file after making a backup.
  echo It may force a JDK 25 provisioning attempt despite Java 17.
  exit /b 2
)
java -version
if errorlevel 1 (echo ERROR: Java 17 required. & exit /b 2)
call gradlew.bat --stop
call gradlew.bat :app:assembleDebug --no-daemon --console=plain
if errorlevel 1 (echo DEBUG BUILD FAILED & exit /b 1)
echo DEBUG BUILD PASSED
call gradlew.bat :app:assembleRelease --no-daemon --console=plain
if errorlevel 1 (echo RELEASE BUILD FAILED & exit /b 1)
echo RELEASE BUILD FINISHED - VERIFY APK SIGNATURE BEFORE DISTRIBUTION
echo app\build\outputs\apk\debug\app-debug.apk
echo app\build\outputs\apk\release\app-release.apk
