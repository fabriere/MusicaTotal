package com.maxrave.simpmusic.expect

import androidx.compose.runtime.Composable

/**
 * Fila de "Buscar actualización" en Ajustes.
 *
 * En Android comprueba el repositorio, baja el APK y abre el instalador del
 * sistema. En escritorio no hace nada: esa build se actualiza por su cuenta.
 */
@Composable
expect fun AppUpdateRow()
