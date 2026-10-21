package ec.musicatotal.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Configuración de la app. Todo lo que hay que cambiar de un cliente a otro
 * vive aquí y en ningún otro sitio.
 */
object MtConfig {
    /** Backend de cuentas y licencias en Railway (servicio MusicaTotal-API). */
    const val API_BASE = "https://musicatotal-api-production.up.railway.app"

    const val SUPPORT_URL = "$API_BASE/ayuda"
    const val PRIVACY_URL = "$API_BASE/privacidad"
}

/**
 * Sesión guardada en el teléfono.
 *
 * El plan y su caducidad viajan dentro de la sesión, así que la comprobación
 * funciona SIN conexión: un cliente con licencia sigue dentro aunque el
 * teléfono se quede sin datos, y una prueba caducada sigue caducada.
 */
data class MtLicense(
    val token: String,
    val plan: String,
    val expiresAt: String?,
    val email: String?,
)

sealed interface ActivationResult {
    data class Ok(val license: MtLicense) : ActivationResult
    data class Failed(val message: String) : ActivationResult
}

/** Reglas de la licencia. Espejo exacto de lib/license.ts de VideoTotal. */
object MtLicenseLogic {
    fun isActive(license: MtLicense?): Boolean {
        if (license == null) return false
        if (license.plan.isBlank() || license.plan == "free") return false
        val expires = license.expiresAt ?: return true // sin caducidad = perpetua
        val fin = parseInstant(expires) ?: return true // fecha rara: dejar pasar
        return fin > System.currentTimeMillis()
    }

    /** Días que quedan, redondeando hacia arriba. null si es perpetua. */
    fun daysLeft(expiresAt: String?): Int? {
        val expires = expiresAt ?: return null
        val fin = parseInstant(expires) ?: return null
        val restante = fin - System.currentTimeMillis()
        if (restante <= 0) return 0
        return ((restante + 86_399_999L) / 86_400_000L).toInt()
    }

    fun parseInstant(value: String): Long? =
        try {
            Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }

    /** Fecha legible: 19/11/2026 */
    fun prettyDate(value: String?): String? {
        val millis = value?.let { parseInstant(it) } ?: return null
        val date = java.util.Date(millis)
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("es", "EC"))
        return fmt.format(date)
    }
}

/** Guarda la sesión en el teléfono. */
class LicenseStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("musica_total_licencia", Context.MODE_PRIVATE)

    fun license(): MtLicense? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (token.isBlank()) return null
        return MtLicense(
            token = token,
            plan = prefs.getString(KEY_PLAN, "free") ?: "free",
            expiresAt = prefs.getString(KEY_EXPIRES, null),
            email = prefs.getString(KEY_EMAIL, null),
        )
    }

    fun save(license: MtLicense) {
        prefs.edit()
            .putString(KEY_TOKEN, license.token)
            .putString(KEY_PLAN, license.plan)
            .putString(KEY_EXPIRES, license.expiresAt)
            .putString(KEY_EMAIL, license.email)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun lastCheck(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_PLAN = "plan"
        private const val KEY_EXPIRES = "planExpiresAt"
        private const val KEY_EMAIL = "email"
        private const val KEY_LAST_CHECK = "lastCheck"

        @Volatile
        private var instance: LicenseStore? = null

        fun get(context: Context): LicenseStore =
            instance ?: synchronized(this) {
                instance ?: LicenseStore(context).also { instance = it }
            }
    }
}

/** Cliente HTTP del backend de licencias. */
object LicenseApi {
    private const val TIMEOUT = 20_000

    /** Identificador estable de este teléfono. */
    fun deviceId(context: Context): String {
        val androidId =
            try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
        return (androidId ?: "desconocido").lowercase()
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * Canjea el código y deja la sesión guardada.
     *
     * No hay correo ni contraseña: el servidor crea una cuenta anónima atada a
     * este dispositivo y le aplica la licencia.
     */
    suspend fun activate(
        context: Context,
        rawCode: String,
    ): ActivationResult =
        withContext(Dispatchers.IO) {
            val code = rawCode.uppercase().replace(Regex("[^A-Z0-9]"), "")
            if (code.isBlank()) return@withContext ActivationResult.Failed("Escribe tu código de licencia.")

            val body =
                JSONObject()
                    .put("code", code)
                    .put("deviceId", deviceId(context))
                    .put("name", deviceName())
                    .toString()

            val result = request("POST", "/license/activate", body, token = null)
            when {
                result.first in 200..299 -> {
                    val json = JSONObject(result.second)
                    val user = json.optJSONObject("user")
                    val license =
                        MtLicense(
                            token = json.optString("access_token"),
                            plan = user?.optString("plan").orEmpty().ifBlank { "free" },
                            expiresAt = user?.optString("planExpiresAt")?.takeIf { it != "null" && it.isNotBlank() },
                            email = user?.optString("email")?.takeIf { it != "null" && it.isNotBlank() },
                        )
                    LicenseStore.get(context).save(license)
                    ActivationResult.Ok(license)
                }

                else -> ActivationResult.Failed(messageFrom(result.first, result.second))
            }
        }

    /**
     * Vuelve a preguntar al servidor por el plan. Sirve para que una licencia
     * renovada desde el panel entre sin reinstalar nada.
     *
     * Si no hay red se devuelve la sesión guardada tal cual: quedarse sin datos
     * no puede dejar fuera a quien pagó.
     */
    suspend fun refresh(context: Context): MtLicense? =
        withContext(Dispatchers.IO) {
            val store = LicenseStore.get(context)
            val saved = store.license() ?: return@withContext null

            val result = request("GET", "/me", null, token = saved.token)
            if (result.first == 401) {
                // Sesión revocada en el servidor: se borra y se pide código.
                store.clear()
                return@withContext null
            }
            if (result.first !in 200..299) return@withContext saved

            try {
                val user = JSONObject(result.second)
                val fresh =
                    MtLicense(
                        token = saved.token,
                        plan = user.optString("plan").ifBlank { "free" },
                        expiresAt = user.optString("planExpiresAt").takeIf { it != "null" && it.isNotBlank() },
                        email = user.optString("email").takeIf { it != "null" && it.isNotBlank() },
                    )
                store.save(fresh)
                store.markChecked()
                fresh
            } catch (e: Exception) {
                saved
            }
        }

    private fun messageFrom(
        status: Int,
        body: String,
    ): String {
        // El backend responde los fallos como {"error":{"code":...,"message":...}}.
        val serverMessage =
            try {
                val json = JSONObject(body)
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        return serverMessage ?: when (status) {
            429 -> "Demasiados intentos. Espera un momento y vuelve a probar."
            in 500..599 -> "El servidor no responde. Revisa tu conexión e inténtalo otra vez."
            else -> "No se pudo activar la licencia. Revisa tu conexión."
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        token: String?,
    ): Pair<Int, String> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(MtConfig.API_BASE + path)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            status to text
        } catch (e: Exception) {
            // Sin red, DNS caído, servidor apagado: todo cae aquí.
            -1 to ""
        } finally {
            connection?.disconnect()
        }
    }
}
