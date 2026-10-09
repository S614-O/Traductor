package com.example.traductor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import java.io.File

// Cada página guarda su resultado en un archivo temporal para no llenar la memoria
class Pagina(val uri: Uri, val archivo: File) {
    var estado by mutableStateOf("Pendiente")
    var lista by mutableStateOf(false)
}

// Abre la imagen y la reduce si es demasiado grande
fun cargarBitmap(context: Context, uri: Uri, maxLado: Int = 2600): Bitmap? {
    val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, limites)
    }
    var muestreo = 1
    while (maxOf(limites.outWidth, limites.outHeight) / muestreo > maxLado * 2) muestreo *= 2

    val opciones = BitmapFactory.Options().apply { inSampleSize = muestreo }
    var bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opciones)
    } ?: return null

    val lado = maxOf(bmp.width, bmp.height)
    if (lado > maxLado) {
        val f = maxLado.toFloat() / lado
        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
    }
    return bmp
}

// Dibuja los globos traducidos sobre una copia de la página
fun renderizar(context: Context, bmp: Bitmap, globos: List<Pair<Rect, String>>): Bitmap {
    val resultado = bmp.copy(Bitmap.Config.ARGB_8888, true)
    val vista = OverlayView(context)
    vista.factor = maxOf(1f, bmp.width / 1080f)
    vista.mostrar(globos, manga = true)
    vista.measure(
        View.MeasureSpec.makeMeasureSpec(bmp.width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(bmp.height, View.MeasureSpec.EXACTLY)
    )
    vista.layout(0, 0, bmp.width, bmp.height)
    vista.draw(Canvas(resultado))
    return resultado
}

fun guardarEnGaleria(context: Context, archivo: File, nombre: String): Boolean {
    if (Build.VERSION.SDK_INT < 29) {
        Toast.makeText(context, "Guardar requiere Android 10 o superior", Toast.LENGTH_LONG).show()
        return false
    }
    val valores = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$nombre.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Traductor")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
        ?: return false
    context.contentResolver.openOutputStream(uri)?.use { salida ->
        archivo.inputStream().use { it.copyTo(salida) }
    }
    return true
}

@Composable
fun ItemPagina(indice: Int, p: Pagina) {
    val context = LocalContext.current
    val imagen by produceState<Bitmap?>(initialValue = null, p.lista) {
        value = if (p.lista) {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p.archivo.path) }
        } else null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text("Página ${indice + 1}: ${p.estado}")
        imagen?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
            OutlinedButton(onClick = {
                val ok = guardarEnGaleria(context, p.archivo, "manga_${System.currentTimeMillis()}")
                if (ok) Toast.makeText(context, "Guardada en Imágenes/Traductor", Toast.LENGTH_SHORT).show()
            }) { Text("Guardar en la galería") }
        }
    }
}

@Composable
fun PantallaGaleria(onVolver: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paginas = remember { mutableStateListOf<Pagina>() }
    var traduciendo by remember { mutableStateOf(false) }

    val selector = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { uri ->
            val archivo = File(context.cacheDir, "pagina_${System.currentTimeMillis()}_${paginas.size}.png")
            paginas.add(Pagina(uri, archivo))
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
            Text("Traducir desde la galería")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                selector.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) { Text("Elegir imágenes") }

            Button(
                enabled = !traduciendo && paginas.isNotEmpty(),
                onClick = {
                    traduciendo = true
                    scope.launch {
                        val motor = MotorTexto(CaptureService.idiomaOrigen)
                        try {
                            withContext(Dispatchers.IO) { motor.prepararModelo() }
                            for (p in paginas.toList()) {
                                if (p.lista) continue
                                p.estado = "Traduciendo…"
                                try {
                                    val bmp = withContext(Dispatchers.IO) {
                                        cargarBitmap(context, p.uri)
                                    } ?: throw Exception("No se pudo abrir la imagen")
                                    val globos = withContext(Dispatchers.Default) {
                                        MangaOffline.traducir(
                                            context, bmp, motor.recognizer, motor.translator, motor.idioma
                                        )
                                    }
                                    val resultado = renderizar(context, bmp, globos)
                                    withContext(Dispatchers.IO) {
                                        p.archivo.outputStream().use {
                                            resultado.compress(Bitmap.CompressFormat.PNG, 100, it)
                                        }
                                    }
                                    resultado.recycle()
                                    bmp.recycle()
                                    p.estado = "Lista (${globos.size} globos)"
                                    p.lista = true
                                } catch (e: Exception) {
                                    Log.e("Manga", "Error", e)
                                    p.estado = "Error: ${e.message?.take(100)}"
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "No se pudo preparar el idioma: ${e.message?.take(100)}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            motor.cerrar()
                            traduciendo = false
                        }
                    }
                }
            ) { Text(if (traduciendo) "Traduciendo…" else "Traducir") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(paginas) { i, p -> ItemPagina(i, p) }
        }
    }
}