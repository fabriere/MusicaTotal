package ec.musicatotal.license

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.logo_musica_total

/* ------------------------------------------------------------------------- */
/* Paleta de la marca                                                        */
/* ------------------------------------------------------------------------- */

private val FondoArriba = Color(0xFF0C0716)
private val FondoAbajo = Color(0xFF08080B)
private val Superficie = Color(0xFF16121F)
private val Violeta = Color(0xFF8B5CF6)
private val Rosa = Color(0xFFEC4899)
private val TextoPrincipal = Color(0xFFF5F2FF)
private val TextoSecundario = Color(0xFFA9A2BD)
private val VerdeOk = Color(0xFF22C55E)
private val RojoError = Color(0xFFFF5A6E)

private val DegradadoMarca = Brush.linearGradient(listOf(Violeta, Rosa))

private sealed interface Gate {
    data object Cargando : Gate

    data class Bloqueada(
        val caducada: Boolean,
        val caducaEl: String?,
    ) : Gate

    data object Activa : Gate
}

/**
 * Puerta de entrada de Música Total.
 *
 * Se comprueba contra el backend de Railway igual que VideoTotal: código de
 * licencia, límite de dispositivos y plan con caducidad. Si el teléfono se
 * queda sin datos, la sesión guardada sigue valiendo.
 */
@Composable
fun MusicaTotalLicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var gate by remember { mutableStateOf<Gate>(Gate.Cargando) }

    LaunchedEffect(Unit) {
        val store = LicenseStore.get(context)
        val saved = store.license()
        gate =
            when {
                saved == null -> Gate.Bloqueada(caducada = false, caducaEl = null)
                MtLicenseLogic.isActive(saved) -> Gate.Activa
                else -> Gate.Bloqueada(caducada = true, caducaEl = saved.expiresAt)
            }

        // Refresco silencioso: si renovó desde el panel, entra sin reinstalar.
        if (gate == Gate.Activa && saved != null) {
            LicenseApi.refresh(context)
            val after = store.license()
            gate =
                when {
                    after == null -> Gate.Bloqueada(caducada = false, caducaEl = null)
                    MtLicenseLogic.isActive(after) -> Gate.Activa
                    else -> Gate.Bloqueada(caducada = true, caducaEl = after.expiresAt)
                }
        }
    }

    when (val actual = gate) {
        Gate.Cargando -> PantallaCargando()
        is Gate.Bloqueada ->
            PantallaActivacion(
                caducada = actual.caducada,
                caducaEl = actual.caducaEl,
                onActivada = { gate = Gate.Activa },
            )

        Gate.Activa -> content()
    }
}

/* ------------------------------------------------------------------------- */
/* Pantallas                                                                 */
/* ------------------------------------------------------------------------- */

@Composable
private fun Fondo(contenido: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(FondoArriba, FondoAbajo))),
    ) {
        contenido()
    }
}

@Composable
private fun Marca(
    titulo: String,
    subtitulo: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(Res.drawable.logo_musica_total),
            contentDescription = "Música Total",
            modifier =
                Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(28.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = titulo,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextoPrincipal,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitulo,
            fontSize = 14.sp,
            color = TextoSecundario,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PantallaCargando() {
    Fondo {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Marca(titulo = "Música Total", subtitulo = "Tu música, sin anuncios")
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(color = Violeta, strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun PantallaActivacion(
    caducada: Boolean,
    caducaEl: String?,
    onActivada: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var codigo by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exito by remember { mutableStateOf(false) }

    fun activar() {
        if (ocupado) return
        ocupado = true
        error = null
        exito = false
        scope.launch {
            when (val resultado = LicenseApi.activate(context, codigo)) {
                is ActivationResult.Ok -> {
                    val licencia = resultado.license
                    if (MtLicenseLogic.isActive(licencia)) {
                        exito = true
                        delay(700)
                        onActivada()
                    } else {
                        error = "Ese código no trae una licencia activa."
                    }
                }

                is ActivationResult.Failed -> error = resultado.message
            }
            ocupado = false
        }
    }

    Fondo {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Marca(
                titulo = if (caducada) "Tu licencia caducó" else "Música Total",
                subtitulo =
                    if (caducada) {
                        val fecha = MtLicenseLogic.prettyDate(caducaEl)
                        if (fecha != null) "Venció el $fecha. Activa un código nuevo." else "Activa un código nuevo para seguir."
                    } else {
                        "Tu música, sin anuncios ni interrupciones"
                    },
            )

            Spacer(Modifier.height(26.dp))

            Surface(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Superficie,
                border = BorderStroke(1.dp, Color(0xFF2A2338)),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = if (caducada) "Activa un código nuevo" else "Activar licencia",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextoPrincipal,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Escribe el código que te entregamos al comprar.",
                        fontSize = 13.sp,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = codigo,
                        onValueChange = {
                            codigo = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(24)
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !ocupado,
                        label = { Text("Código de licencia") },
                        placeholder = { Text("XXXX-XXXX-XXXX", color = Color(0xFF5A5370)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, letterSpacing = 2.sp),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                // El texto y el cursor van en claro: sin esto, el
                                // tema por defecto pinta el código en gris oscuro
                                // sobre fondo oscuro y no se lee.
                                focusedTextColor = TextoPrincipal,
                                unfocusedTextColor = TextoPrincipal,
                                cursorColor = Violeta,
                                focusedLabelColor = Violeta,
                                unfocusedLabelColor = TextoSecundario,
                                focusedPlaceholderColor = Color(0xFF6B6383),
                                unfocusedPlaceholderColor = Color(0xFF6B6383),
                                focusedIndicatorColor = Violeta,
                                unfocusedIndicatorColor = Color(0xFF3A3150),
                            ),
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { activar() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = codigo.length >= 4 && !ocupado,
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Violeta,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF2C2440),
                                disabledContentColor = Color(0xFF7A7291),
                            ),
                    ) {
                        if (ocupado) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(if (caducada) "Activar código" else "Activar licencia", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Los avisos van JUSTO debajo del botón: arriba de la
                    // pantalla no se ven.
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Text(text = "⚠️", fontSize = 13.sp)
                            Spacer(Modifier.widthIn(min = 6.dp))
                            Text(
                                text = error.orEmpty(),
                                fontSize = 13.sp,
                                color = RojoError,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                    if (exito) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "✓ Licencia activada",
                            fontSize = 13.sp,
                            color = VerdeOk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MtConfig.SUPPORT_URL)))
                    } catch (e: Exception) {
                        // Sin navegador no hay nada que abrir.
                    }
                },
            ) {
                Text("¿Necesitas una licencia? Escríbenos", fontSize = 13.sp, color = TextoSecundario)
            }
        }
    }
}
