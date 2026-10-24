package com.maxrave.simpmusic.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import com.maxrave.logger.Logger
import java.io.File

/**
 * Resultado de una instalación por sesión.
 *
 * Con `PackageInstaller` el sistema no devuelve el resultado en la llamada:
 * lo manda a este receptor. En `STATUS_PENDING_USER_ACTION` trae además el
 * Intent con el que hay que enseñar la confirmación al usuario.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Logger.w(TAG, "No se pudo abrir la confirmación: ${it.message}") }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Logger.i(TAG, "Actualización instalada")
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Logger.w(TAG, "Instalación rechazada (status=$status): $message")
            }
        }
    }

    private companion object {
        const val TAG = "InstallResult"
    }
}

/**
 * Instala el APK con una sesión de `PackageInstaller`, que es como Android
 * espera que una app se actualice a sí misma.
 *
 * En algunos teléfonos (Honor/Huawei con su App Market) el Intent clásico
 * `ACTION_VIEW` con un `content://` acaba en el instalador del fabricante, y su
 * verificador lo rechaza con `INSTALL_FAILED_VERIFICATION_FAILURE (-22)`. La
 * sesión va por el instalador del sistema y no pasa por ahí.
 */
object ApkSessionInstaller {
    private const val TAG = "ApkSessionInstaller"

    /** @return true si la sesión se creó y se confirmó (el resultado llega al receptor). */
    fun install(
        context: Context,
        file: File,
    ): Boolean =
        try {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        session.fsync(output)
                    }
                }

                val resultIntent =
                    Intent(context, InstallResultReceiver::class.java)
                        .setAction(ACTION_INSTALL_RESULT)
                        .putExtra(EXTRA_SESSION_ID, sessionId)

                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PendingIntent.FLAG_MUTABLE
                        } else {
                            0
                        }

                val pending =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        resultIntent,
                        flags,
                    )
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo instalar por sesión: ${e.message}")
            false
        }

    const val ACTION_INSTALL_RESULT = "com.maxrave.simpmusic.updates.INSTALL_RESULT"
    const val EXTRA_SESSION_ID = "session_id"
}
