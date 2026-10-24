package com.maxrave.simpmusic.expect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.updates.ApkInstaller
import com.maxrave.simpmusic.updates.AppUpdate
import com.maxrave.simpmusic.updates.AppUpdateChecker
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.check_for_update
import simpmusic.composeapp.generated.resources.update_checking
import simpmusic.composeapp.generated.resources.update_download_and_install
import simpmusic.composeapp.generated.resources.update_downloading
import simpmusic.composeapp.generated.resources.update_error_check
import simpmusic.composeapp.generated.resources.update_install_ready
import simpmusic.composeapp.generated.resources.update_installed_version
import simpmusic.composeapp.generated.resources.update_later
import simpmusic.composeapp.generated.resources.update_new_version_message
import simpmusic.composeapp.generated.resources.update_new_version_title
import simpmusic.composeapp.generated.resources.update_permission_needed
import simpmusic.composeapp.generated.resources.update_up_to_date
import java.io.File
import java.util.Locale

/** Estados de la fila. */
private sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val version: String) : UpdateState

    data class Available(val update: AppUpdate) : UpdateState

    data class Downloading(
        val update: AppUpdate,
        val fraction: Float?,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState

    data class Ready(val update: AppUpdate) : UpdateState

    data class PermissionNeeded(val update: AppUpdate) : UpdateState

    data object Failed : UpdateState
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0L) {
        "—"
    } else {
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    }

/**
 * "Buscar actualización": comprueba GitHub, descarga el APK con el
 * DownloadManager del sistema y abre el instalador de Android.
 *
 * Android siempre pide confirmación para instalar; eso no se salta. Si el
 * usuario tiene que autorizar "apps desconocidas", al volver el APK ya está en
 * disco y se instala sin descargarlo otra vez.
 */
@Composable
actual fun AppUpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedVersion = remember { VersionManager.getVersionName() }

    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var showNewVersionDialog by remember { mutableStateOf(false) }
    var installLaunched by remember { mutableStateOf(false) }

    fun openInstaller(update: AppUpdate) {
        if (installLaunched) return
        val file = ApkInstaller.alreadyDownloaded(context, update)
        if (file == null) {
            state = UpdateState.Available(update)
            return
        }
        if (ApkInstaller.install(context, file)) {
            installLaunched = true
        } else {
            // Falta el permiso: se abre la pantalla del sistema y se deja el
            // APK donde está, para que al volver no haya que bajarlo otra vez.
            state = UpdateState.PermissionNeeded(update)
            ApkInstaller.openUnknownSourcesSettings(context)
        }
    }

    fun startDownload(update: AppUpdate) {
        // Ya descargado (por ejemplo, se canceló el instalador y se volvió):
        // se instala directo.
        if (ApkInstaller.alreadyDownloaded(context, update) != null) {
            openInstaller(update)
            return
        }
        val id = ApkInstaller.startDownload(context, update)
        if (id == null) {
            state = UpdateState.Failed
            return
        }
        downloadId = id
        state =
            UpdateState.Downloading(
                update = update,
                fraction = 0f,
                downloadedBytes = 0L,
                totalBytes = update.sizeBytes,
            )
    }

    fun check() {
        if (state == UpdateState.Checking) return
        state = UpdateState.Checking
        scope.launch {
            val update = AppUpdateChecker.latest(installedVersion)
            if (update == null) {
                state = UpdateState.UpToDate(installedVersion)
            } else {
                state = UpdateState.Available(update)
                showNewVersionDialog = true
            }
        }
    }

    // Progreso de la descarga mientras haya una en curso.
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        while (isActive) {
            val current = state
            if (current !is UpdateState.Downloading) break
            val progress = ApkInstaller.progress(context, id)
            if (progress == null) {
                state = UpdateState.Failed
                downloadId = null
                break
            }
            when {
                progress.finished -> {
                    downloadId = null
                    state = UpdateState.Ready(current.update)
                    openInstaller(current.update)
                    break
                }

                progress.failed -> {
                    downloadId = null
                    state = UpdateState.Failed
                    break
                }

                else -> {
                    state =
                        UpdateState.Downloading(
                            update = current.update,
                            fraction = progress.fraction,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = if (progress.totalBytes > 0) progress.totalBytes else current.update.sizeBytes,
                        )
                }
            }
            delay(400)
        }
    }

    // El sistema avisa cuando termina; sirve de red por si la app estaba en
    // segundo plano y el sondeo se paró.
    DisposableEffect(downloadId) {
        val id = downloadId
        if (id == null) {
            onDispose { }
        } else {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        if (intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                        val current = state
                        if (current is UpdateState.Downloading) {
                            val file = ApkInstaller.alreadyDownloaded(ctx, current.update)
                            if (file != null) {
                                state = UpdateState.Ready(current.update)
                                openInstaller(current.update)
                            }
                        }
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    val subtitle =
        when (val current = state) {
            UpdateState.Idle -> stringResource(Res.string.update_installed_version, installedVersion)
            UpdateState.Checking -> stringResource(Res.string.update_checking)
            is UpdateState.UpToDate -> stringResource(Res.string.update_up_to_date, current.version)
            is UpdateState.Available -> stringResource(Res.string.update_install_ready, current.update.versionName)
            is UpdateState.Downloading ->
                stringResource(
                    Res.string.update_downloading,
                    current.fraction?.let { "${(it * 100).toInt()}%" } ?: megabytes(current.totalBytes),
                )

            is UpdateState.Ready -> stringResource(Res.string.update_install_ready, current.update.versionName)
            is UpdateState.PermissionNeeded -> stringResource(Res.string.update_permission_needed)
            UpdateState.Failed -> stringResource(Res.string.update_error_check)
        }

    SettingItem(
        title = stringResource(Res.string.check_for_update),
        subtitle = subtitle,
        onClick = {
            when (val current = state) {
                UpdateState.Idle, UpdateState.Failed, is UpdateState.UpToDate -> check()
                is UpdateState.Available -> showNewVersionDialog = true
                is UpdateState.Ready -> openInstaller(current.update)
                is UpdateState.PermissionNeeded ->
                    // Al volver de los ajustes se instala el archivo que ya está.
                    openInstaller(current.update)

                is UpdateState.Downloading -> Unit
                UpdateState.Checking -> Unit
            }
        },
        otherView = {
            when (val current = state) {
                UpdateState.Checking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }

                is UpdateState.Downloading -> {
                    ProgressBar(fraction = current.fraction)
                }

                else -> Unit
            }
        },
    )

    if (showNewVersionDialog && state is UpdateState.Available) {
        val update = (state as UpdateState.Available).update
        AlertDialog(
            onDismissRequest = { showNewVersionDialog = false },
            title = { Text(stringResource(Res.string.update_new_version_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.update_new_version_message,
                        update.versionName,
                        megabytes(update.sizeBytes),
                    ),
                    style = typo().bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewVersionDialog = false
                        startDownload(update)
                    },
                ) {
                    Text(stringResource(Res.string.update_download_and_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewVersionDialog = false }) {
                    Text(stringResource(Res.string.update_later))
                }
            },
        )
    }

    if (state is UpdateState.Downloading) {
        val current = state as UpdateState.Downloading
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(Res.string.update_new_version_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            Res.string.update_new_version_message,
                            current.update.versionName,
                            megabytes(current.totalBytes),
                        ),
                        style = typo().bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    ProgressBar(fraction = current.fraction)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            Res.string.update_downloading,
                            current.fraction?.let { "${(it * 100).toInt()}%" } ?: megabytes(current.downloadedBytes),
                        ),
                        style = typo().bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadId?.let { ApkInstaller.cancel(context, it) }
                        downloadId = null
                        state = UpdateState.Available(current.update)
                    },
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

/** Barra de progreso determinada, dibujada a mano para no depender de la firma de la de Material. */
@Composable
private fun ProgressBar(fraction: Float?) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fraction != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Para pruebas manuales: la ruta donde debería quedar el APK. */
internal fun expectedApkFile(
    context: Context,
    update: AppUpdate,
): File? = ApkInstaller.targetFile(context, update)
