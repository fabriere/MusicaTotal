@echo off
rem ===========================================================================
rem  Compila el APK de Musica Total y lo deja firmado en el Escritorio.
rem  Doble clic y espera. La primera vez tarda bastante (descarga todo).
rem ===========================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1
cd /d "%~dp0"

set "ANDROID_HOME=C:\tools\android-sdk"
set "ANDROID_SDK_ROOT=C:\tools\android-sdk"
set "JAVA_HOME=C:\tools\jdk-21"
set "BT=C:\tools\android-sdk\build-tools\37.0.0"
set "KS=signing\musicatotal-release.jks"
set "KSPASS=MusicTotal2026"
set "ALIAS=musicatotal"

echo.
echo   ==========================================
echo        Compilando Musica Total (APK)
echo   ==========================================
echo.

call gradlew.bat :androidApp:assembleRelease -PisFullBuild=false --no-configuration-cache -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g"
if errorlevel 1 (
  echo.
  echo   [X] La compilacion fallo. Lee los errores de arriba.
  echo.
  pause
  exit /b 1
)

set "UNSIGNED="
for /r "androidApp\build\outputs\apk\release" %%f in (*universal*unsigned*.apk) do set "UNSIGNED=%%f"
if "%UNSIGNED%"=="" (
  for /r "androidApp\build\outputs\apk\release" %%f in (*.apk) do set "UNSIGNED=%%f"
)
if "%UNSIGNED%"=="" (
  echo.
  echo   [X] No se encontro el APK generado.
  echo.
  pause
  exit /b 1
)
echo.
echo   APK generado: %UNSIGNED%

set "ALIGNED=%TEMP%\musica-total-aligned.apk"
set "FINAL=%USERPROFILE%\Desktop\MusicaTotal.apk"

"%BT%\zipalign.exe" -f 4 "%UNSIGNED%" "%ALIGNED%"
if errorlevel 1 (
  echo   [X] Fallo el alineado.
  pause
  exit /b 1
)

"%BT%\apksigner.bat" sign --ks "%KS%" --ks-pass pass:%KSPASS% --key-pass pass:%KSPASS% --ks-key-alias %ALIAS% --out "%FINAL%" "%ALIGNED%"
if errorlevel 1 (
  echo   [X] Fallo la firma.
  pause
  exit /b 1
)

del "%ALIGNED%" >nul 2>&1

echo.
echo   ==========================================
echo     LISTO: %FINAL%
echo   ==========================================
echo.
echo   Pasalo al telefono e instalalo (permitir origen desconocido).
echo.
start "" explorer /select,"%FINAL%"
pause
