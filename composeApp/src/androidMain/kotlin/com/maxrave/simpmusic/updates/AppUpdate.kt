package com.maxrave.simpmusic.updates

import com.maxrave.simpmusic.utils.compareVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Una versión publicada en GitHub que este teléfono todavía no tiene.
 *
 * [sizeBytes] se usa para dos cosas: enseñar el peso antes de descargar y
 * reconocer un APK que ya está en disco (mismo nombre y mismo tamaño = no hay
 * que volver a bajar 80 MB).
 */
data class AppUpdate(
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseUrl: String,
    val fileName: String,
)

/**
 * Comprueba si hay una versión nueva en el repositorio público.
 *
 * El repositorio es la única fuente de verdad: el tag del release es la versión
 * (1.4.2) y el adjunto tiene que terminar en .apk.
 */
object AppUpdateChecker {
    private const val URL = "https://api.github.com/repos/fabriere/MusicaTotal/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Devuelve la actualización disponible, o `null` si ya está al día o si no
     * se pudo leer la respuesta.
     *
     * La comparación es NUMÉRICA: con comparación de texto, "1.4.10" quedaría
     * por debajo de "1.4.9" y el aviso no saldría nunca.
     */
    suspend fun latest(currentVersionName: String): AppUpdate? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()

            val body =
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        response.body?.string()
                    }
                } catch (e: Exception) {
                    return@withContext null
                } ?: return@withContext null

            parse(body, currentVersionName)
        }

    /** Separado del HTTP para poder probarlo sin red. */
    internal fun parse(
        json: String,
        currentVersionName: String,
    ): AppUpdate? =
        try {
            val release = JSONObject(json)
            val version = release.optString("tag_name").trim().removePrefix("v")
            if (version.isBlank()) return null

            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue

                // Ya instalada (o más nueva, en una compilación de desarrollo).
                if (compareVersions(version, currentVersionName) <= 0) return null

                return AppUpdate(
                    versionName = version,
                    downloadUrl = url,
                    sizeBytes = asset.optLong("size", 0L),
                    releaseUrl = release.optString("html_url"),
                    fileName = name,
                )
            }
            null
        } catch (e: Exception) {
            null
        }
}
