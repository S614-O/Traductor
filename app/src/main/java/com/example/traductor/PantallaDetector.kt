package com.example.traductor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Dibuja las cajas detectadas: verde = globo con texto, naranja = texto suelto
fun dibujarCajas(bmp: Bitmap, globos: List<Globo>): Bitmap {
    val copia = bmp.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(copia)
    val grosor = maxOf(3f, bmp.width / 300f)
    val pincel = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = grosor
    }
    val texto = Paint().apply {
        textSize = grosor * 7
        isAntiAlias = true
    }
    for (g in globos) {
        val color = if (g.clase == 1) Color.rgb(0, 180, 0) else Color.rgb(255, 140, 0)
        pincel.color = color
        texto.color = color
        canvas.drawRect(g.rect, pincel)
        canvas.drawText("%.2f".format(g.puntuacion), g.rect.left, g.rect.top - 6f, texto)
    }
    return copia
}

@Composable
fun PantallaDetector(onVolver: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imagen by remember { mutableStateOf<Bitmap?>(null) }
    var info by remember { mutableStateOf("Elige una página para probar el detector") }
    var trabajando by remember { mutableStateOf(false) }

    val selector = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            trabajando = true
            info = "Detectando…"
            scope.launch {
                try {
                    val r = withContext(Dispatchers.Default) {
                        val bmp = cargarBitmap(context, uri)
                            ?: throw Exception("No se pudo abrir la imagen")
                        val t0 = System.currentTimeMillis()
                        val detector = DetectorGlobos(context)
                        val tCarga = System.currentTimeMillis() - t0

                        val t1 = System.currentTimeMillis()
                        val globos = detector.detectar(bmp)
                        val tDeteccion = System.currentTimeMillis() - t1
                        detector.cerrar()

                        val textoGlobos = globos.count { it.clase == 1 }
                        val textoSuelto = globos.count { it.clase == 2 }
                        Pair(
                            dibujarCajas(bmp, globos),
                            "$textoGlobos globos y $textoSuelto textos sueltos. " +
                                    "Carga del modelo: $tCarga ms, detección: $tDeteccion ms"
                        )
                    }
                    imagen = r.first
                    info = r.second
                } catch (e: Exception) {
                    Log.e("Detector", "Error", e)
                    info = "Error: ${e.message}"
                }
                trabajando = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onVolver) { Text("← Volver") }
            Spacer(Modifier.width(12.dp))
            Text("Prueba del detector")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !trabajando,
            onClick = {
                selector.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        ) { Text("Elegir página") }
        Spacer(Modifier.height(8.dp))
        Text(info)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            imagen?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}