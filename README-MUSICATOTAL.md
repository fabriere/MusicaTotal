# Música Total (Android)

Clon de **SimpMusic** (cliente de YouTube Music, GPL-3.0) convertido en
**Música Total**: misma app, marca propia y **licencias por código** contra un
backend en Railway, igual que VideoTotal.

Solo Android (APK). El módulo de escritorio se queda en el repo sin usar.

## Estructura

```
composeApp/                    UI y lógica compartida (Compose Multiplatform)
  src/androidMain/kotlin/ec/musicatotal/license/
      LicenseManager.kt        API del backend + guardado de la licencia
      LicenseGate.kt           pantalla de activación (bloquea la app sin licencia)
androidApp/                    módulo Android (APK)
  res/values/app_name.xml      "Música Total"
signing/musicatotal-release.jks  keystore de firma
```

## Backend (Railway)

- Servicio: `MusicaTotal-API` — https://musicatotal-api-production.up.railway.app
- Base de datos: `MusicaTotal-DB` (PostgreSQL, mismo proyecto de Railway)
- Proyecto: `nurturing-light` (el mismo donde vive VideoTotal)

La URL está en **un solo sitio**: `MtConfig.API_BASE` en `LicenseManager.kt`.

### Cómo funciona la licencia

1. El cliente escribe el código en la pantalla de activación.
2. La app manda `POST /license/activate {code, deviceId, name}`.
3. El servidor crea una cuenta anónima atada al dispositivo y devuelve
   `access_token`, `plan` y `planExpiresAt`.
4. La app guarda la sesión en el teléfono. **Sin conexión sigue funcionando**
   mientras la licencia no caduque.
5. Al abrir, si hay red, se refresca con `GET /me` (una renovación desde el
   panel entra sin reinstalar; una sesión revocada expulsa al dispositivo).

Límite de dispositivos y caducidad los decide el servidor, nunca el teléfono.

## Compilar el APK

Requisitos: JDK 21, Android SDK (platform 37 + build-tools 37) y ~6 GB de RAM
libre. En este equipo:

```bash
export ANDROID_HOME="C:\tools\android-sdk"
export JAVA_HOME="C:\tools\jdk-21"
./gradlew :androidApp:assembleRelease -PisFullBuild=false --no-configuration-cache
```

Luego alinear y firmar:

```bash
BT=/c/tools/android-sdk/build-tools/37.0.0
$BT/zipalign -f 4 androidApp/build/outputs/apk/release/androidApp-universal-release-unsigned.apk /tmp/mt-aligned.apk
$BT/apksigner sign --ks signing/musicatotal-release.jks --ks-pass pass:MusicTotal2026 \
  --key-pass pass:MusicTotal2026 --ks-key-alias musicatotal --out MusicaTotal.apk /tmp/mt-aligned.apk
```

O simplemente doble clic en `COMPILAR APK.bat` (hace todo y deja el APK en el
Escritorio).

## Licencias (códigos)

```
GENERAR LICENCIAS MUSICA TOTAL.bat      # menú: prueba, mensual, anual, perpetua
```

Abre un túnel a `MusicaTotal-DB`, crea los códigos, los muestra y los guarda en
Descargas.

## Marca pendiente

- Logo definitivo: reemplazar `drawable/ic_launcher_foreground_mt.xml` y
  `drawable/ic_launcher_background.xml` (o el PNG que envíe el cliente).
- El APK se llama `MusicaTotal-<versión>.apk` y la versión se sube en
  `gradle/libs.versions.toml` (`version-name` / `version-code`).

## Licencia del código

SimpMusic es **GPL-3.0**. Al distribuir el APK hay que ofrecer el código
fuente. Si este repo se publica (recomendado), la obligación queda cubierta.
