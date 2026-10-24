package com.maxrave.simpmusic.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.maxrave.logger.Logger
import java.io.File

/** Estado de una descarga del sistema. */
data class DownloadProgress(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    /** 0f..1f, o null mientras el servidor no dice cuánto pesa. */
    val fraction: Float?
        get() =
            when {
                totalBytes <= 0L -> null
                else -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            }

    val finished: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL

    val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
}

/**
 * Baja el APK nuevo y abre el instalador de Android.
 *
 * Usa el DownloadManager del sistema (no el de Media3, que es para audio
 * offline): aguanta que el usuario salga de la app, informa del progreso y
 * deja el archivo en un sitio del que FileProvider puede entregarlo.
 *
 * Android siempre pide confirmación al usuario para instalar; eso no se salta.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"
    private const val MIME_APK = "application/vnd.android.package-archive"

    private fun downloadsDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** Ruta donde quedaría (o está) el APK de [update]. */
    fun targetFile(
        context: Context,
        update: AppUpdate,
    ): File? = downloadsDir(context)?.let { File(it, update.fileName) }

    /**
     * El APK ya está en disco y del tamaño que anuncia el release.
     *
     * Es lo que permite volver de los ajustes de "apps desconocidas" sin bajar
     * otra vez el archivo entero.
     */
    fun alreadyDownloaded(
        context: Context,
        update: AppUpdate,
    ): File? {
        val file = targetFile(context, update) ?: return null
        if (!file.exists()) return null
        // Sin tamaño en el release (0) nos fiamos de que exista y no esté vacío.
        val matches = if (update.sizeBytes > 0L) file.length() == update.sizeBytes else file.length() > 0
        return if (matches) file else null
    }

    /** Encola la descarga y devuelve el id con el que se consulta el progreso. */
    fun startDownload(
        context: Context,
        update: AppUpdate,
    ): Long? {
        // El DownloadManager falla si el destino ya existe: se borra el resto
        // de un intento anterior incompleto.
        targetFile(context, update)?.takeIf { it.exists() }?.delete()

        val request =
            DownloadManager
                .Request(Uri.parse(update.downloadUrl))
                .apply {
                    setTitle(update.fileName)
                    setDescription("Música Total ${update.versionName}")
                    setMimeType(MIME_APK)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, update.fileName)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo encolar la descarga: ${e.message}")
            null
        }
    }

    fun progress(
        context: Context,
        downloadId: Long,
    ): DownloadProgress? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val cursor =
            try {
                manager.query(DownloadManager.Query().setFilterById(downloadId))
            } catch (e: Exception) {
                return null
            } ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return DownloadProgress(status, downloaded, total)
        }
    }

    fun cancel(
        context: Context,
        downloadId: Long,
    ) {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(downloadId)
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo cancelar la descarga: ${e.message}")
        }
    }

    /** ¿El teléfono permite instalar desde esta app? (Android 8+) */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Pantalla del sistema para autorizar "instalar apps desconocidas". */
    fun openUnknownSourcesSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Algunos fabricantes no exponen esa pantalla: se abre la lista de apps.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Exception) {
                Logger.w(TAG, "No se pudo abrir los ajustes de instalación: ${e2.message}")
            }
        }
    }

    /**
     * Abre el instalador del sistema con el APK ya descargado.
     *
     * @return true si Android aceptó abrir el instalador.
     */
    fun install(
        context: Context,
        file: File,
    ): Boolean {
        if (!canInstallPackages(context)) {
            // Se devuelve false y quien llama abre los ajustes: así, al volver,
            // el APK sigue en disco y no hay que descargarlo de nuevo.
            return false
        }

        // 1) Sesión de PackageInstaller: es la vía que Android espera para que
        //    una app se actualice a sí misma. En Honor/Huawei el Intent clásico
        //    acaba en el instalador del App Market y su verificador lo rechaza
        //    (INSTALL_FAILED_VERIFICATION_FAILURE, -22).
        if (ApkSessionInstaller.install(context, file)) return true

        // 2) Si la sesión no se pudo abrir, se intenta el Intent de siempre.
        val uri =
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
            } catch (e: Exception) {
                Logger.w(TAG, "FileProvider no pudo entregar ${file.name}: ${e.message}")
                return false
            }

        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
            Logger.w(TAG, "SecurityException al instalar: ${e.message}")
            false
        } catch (e: Exception) {
            Logger.w(TAG, "ActivityNotFoundException al instalar: ${e.message}")
            false
        }
    }
}
