# Música Total (Android)

Cliente de música para Android que reproduce YouTube Music sin anuncios, con
letras sincronizadas, descargas y ecualizador. Es un **fork de
[SimpMusic](https://github.com/maxrave-dev/SimpMusic)**, con marca propia y
**licencias por código** contra un backend en Railway.

Este repositorio se publica porque SimpMusic es **GPL-3.0**: quien distribuye el
APK tiene que poder acceder al código fuente.

- Solo Android (APK). El módulo de escritorio de SimpMusic se queda en el árbol
  sin usarse.
- `applicationId`: `ec.musicatotal.app`
- Backend de licencias: `MusicaTotal-API` (Railway)
- Detalles de compilación, backend y licencias: [README-MUSICATOTAL.md](README-MUSICATOTAL.md)

## Qué cambia respecto a SimpMusic

| | SimpMusic | Música Total |
|---|---|---|
| Nombre y paquete | `com.maxrave.simpmusic` | `ec.musicatotal.app` |
| Acceso | libre | **código de licencia** (plan, caducidad y límite de dispositivos en el servidor) |
| Actualizaciones | desde GitHub de SimpMusic | desactivadas |
| Comprobación de "compilación oficial" | sí | eliminada (el APK se firma con clave propia) |
| Idioma por defecto | tag exacto del sistema (`es-ES`) | por idioma, con `es` para la región de venta |
| Traducción al español | parcial (Crowdin) | **completa** (983/983 textos) |
| Iconos | logo de SimpMusic | logo de Música Total (capibara) |

El resto de la app es SimpMusic tal cual: misma arquitectura, mismo motor de
reproducción (Media3 en Android), mismas fuentes de letras.

## Compilar

Requisitos: JDK 21, Android SDK (platform 37 + build-tools 37) y ~6 GB de RAM
libre.

```bash
git submodule update --init --recursive   # el módulo core es un submódulo
export ANDROID_HOME=/ruta/al/android-sdk
./gradlew :androidApp:assembleRelease -PisFullBuild=false --no-configuration-cache
```

El APK queda en `androidApp/build/outputs/apk/release/`. Hay que firmarlo con
`zipalign` + `apksigner` (ver README-MUSICATOTAL.md). En Windows, `COMPILAR APK.bat`
hace las dos cosas y deja el APK firmado en el Escritorio.

Las claves de firma (`signing/`, `*.jks`) y `local.properties` están en
`.gitignore`: no se publican.

## Licencias

La app pide un código la primera vez y guarda la sesión en el teléfono, así que
**funciona sin conexión** mientras la licencia no caduque. El plan y la
caducidad los decide el servidor; `GET /me` los refresca en cada arranque con
red.

Endpoints: `POST /license/activate`, `GET /me`, `POST /devices`,
`POST /auth/claim`. Código en
`composeApp/src/androidMain/kotlin/ec/musicatotal/license/`.

## Créditos y licencia

SimpMusic es obra de **Tuan Minh Nguyen Duc (maxrave-dev)** y sus
colaboradores, publicado bajo **GPL-3.0**. Este fork conserva esa licencia: ver
[LICENSE](LICENSE). Las modificaciones de Música Total están en el historial de
este repositorio.
