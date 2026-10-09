package com.example.traductor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.traductor.ui.theme.TraductorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TraductorTheme {
                var pantalla by remember { mutableStateOf("inicio") }
                when (pantalla) {
                    "galeria" -> PantallaGaleria(onVolver = { pantalla = "inicio" })
                    "detector" -> PantallaDetector(onVolver = { pantalla = "inicio" })
                    else -> PantallaInicio(
                        onAbrirGaleria = { pantalla = "galeria" },
                        onAbrirDetector = { pantalla = "detector" }
                    )
                }
            }
        }
    }
}

@Composable
fun Opcion(seleccionada: Boolean, texto: String, onClick: () -> Unit) {
    if (seleccionada) {
        Button(onClick = onClick) { Text(texto) }
    } else {
        OutlinedButton(onClick = onClick) { Text(texto) }
    }
}

@Composable
fun PantallaInicio(onAbrirGaleria: () -> Unit, onAbrirDetector: () -> Unit) {
    val context = LocalContext.current
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    val prefs = remember { context.getSharedPreferences("ajustes", Context.MODE_PRIVATE) }

    var idioma by remember { mutableStateOf(CaptureService.idiomaOrigen) }
    var manga by remember { mutableStateOf(CaptureService.modoManga) }

    var fuente by remember { mutableStateOf(prefs.getString("fuente", "sans") ?: "sans") }
    var escala by remember { mutableStateOf(prefs.getFloat("escala", 1f)) }
    var negrita by remember { mutableStateOf(prefs.getBoolean("negrita", false)) }
    var tema by remember { mutableStateOf(prefs.getInt("tema", 0)) }
    var opacidad by remember { mutableStateOf(prefs.getFloat("opacidad", 0.92f)) }

    // Pasa los ajustes al servicio y los guarda cada vez que cambian
    SideEffect {
        CaptureService.modoManga = manga
        EstiloTraduccion.fuente = fuente
        EstiloTraduccion.escalaTexto = escala
        EstiloTraduccion.negrita = negrita
        EstiloTraduccion.tema = tema
        EstiloTraduccion.opacidad = opacidad
        prefs.edit()
            .putString("fuente", fuente)
            .putFloat("escala", escala)
            .putBoolean("negrita", negrita)
            .putInt("tema", tema)
            .putFloat("opacidad", opacidad)
            .apply()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra("resultCode", result.resultCode)
                .putExtra("data", result.data)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Idioma de origen")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("en" to "Inglés", "zh" to "Chino", "ja" to "Japonés")
                .forEach { (codigo, nombre) ->
                    Opcion(idioma == codigo, nombre) {
                        idioma = codigo
                        CaptureService.idiomaOrigen = codigo
                    }
                }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modo manga (globos)")
            Spacer(Modifier.width(8.dp))
            Switch(checked = manga, onCheckedChange = { manga = it })
        }

        Spacer(Modifier.height(32.dp))
        Text("Apariencia de la traducción")

        Spacer(Modifier.height(12.dp))
        Text("Letra")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sans" to "Normal", "serif" to "Serif", "mono" to "Mono")
                .forEach { (codigo, nombre) ->
                    Opcion(fuente == codigo, nombre) { fuente = codigo }
                }
        }

        Spacer(Modifier.height(12.dp))
        Text("Color del globo")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0 to "Auto", 1 to "Blanco", 2 to "Oscuro", 3 to "Amarillo", 4 to "Azul")
                .forEach { (codigo, nombre) ->
                    Opcion(tema == codigo, nombre) { tema = codigo }
                }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Negrita")
            Spacer(Modifier.width(8.dp))
            Switch(checked = negrita, onCheckedChange = { negrita = it })
        }

        Spacer(Modifier.height(12.dp))
        Text("Tamaño máximo de letra: ${(escala * 100).toInt()}%")
        Slider(
            value = escala,
            onValueChange = { escala = it },
            valueRange = 0.5f..1.5f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Opacidad del fondo: ${(opacidad * 100).toInt()}%")
        Slider(
            value = opacidad,
            onValueChange = { opacidad = it },
            valueRange = 0.3f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onAbrirGaleria) { Text("Traducir desde la galería") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAbrirDetector) { Text("Probar detector de globos") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } else {
                launcher.launch(projectionManager.createScreenCaptureIntent())
            }
        }) {
            Text("Iniciar traducción")
        }
    }
}