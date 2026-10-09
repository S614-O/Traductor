package com.example.traductor

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        // La pantalla principal cambia estos valores
        @Volatile
        var idiomaOrigen: String = TranslateLanguage.ENGLISH

        @Volatile
        var modoManga: Boolean = false

        private const val PASO_FIRMA = 32
    }

    private val escala = 1.5f

    private var recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var translator: Translator? = null
    private var idiomaActual = ""
    private var modeloListo = false
    private var ocupado = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var ultimaImagen: Image? = null
    private lateinit var overlay: OverlayView
    private lateinit var boton: LinearLayout
    private lateinit var botonAuto: TextView
    private lateinit var botonParams: WindowManager.LayoutParams
    private var selector: SeleccionView? = null

    // Modo automático
    private var auto = false
    private var cambiandoPantalla = false
    private var ignorarHasta = 0L
    private var firmaBase: IntArray? = null
    private var firmaPrevia: IntArray? = null
    private var firmaCaptura: IntArray? = null
    private var anchoFirma = 0
    private var cajasMostradas: List<Rect> = emptyList()
    private var inicioCiclo = 0L

    private val colorAutoOff = Color.argb(220, 110, 110, 110)
    private val colorAutoOn = Color.argb(220, 240, 160, 0)

    // Se ejecuta cuando la pantalla lleva un momento sin cambiar
    private val alQuietar = Runnable {
        if (!auto) return@Runnable
        cambiandoPantalla = false
        traducirAhora(automatico = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Debe llamarse antes de usar MediaProjection
        iniciarNotificacion()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        configurarIdioma(idiomaOrigen)

        val resultCode =
            intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        } ?: return START_NOT_STICKY

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = detener()
        }, null)

        crearOverlay()
        crearBoton()

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = projection?.createVirtualDisplay(
            "captura",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        // Guarda siempre el fotograma más reciente; en modo auto además vigila los cambios
        imageReader?.setOnImageAvailableListener({ reader ->
            val nueva = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            ultimaImagen?.close()
            ultimaImagen = nueva
            if (auto) procesarFrameAuto(nueva)
        }, handler)

        return START_NOT_STICKY
    }

    // Prepara el reconocedor y el traductor para el idioma de origen elegido
    private fun configurarIdioma(codigo: String) {
        modeloListo = false
        idiomaActual = codigo

        translator?.close()
        recognizer.close()

        recognizer = when (codigo) {
            TranslateLanguage.CHINESE ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            TranslateLanguage.JAPANESE ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        val nuevo = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(codigo)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
        )
        translator = nuevo
        nuevo.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (translator === nuevo) {
                    modeloListo = true
                    Log.d("Traduccion", "Modelo listo: $codigo -> es")
                }
            }
            .addOnFailureListener { Log.e("Traduccion", "Error al descargar modelo", it) }
    }

    private fun listoParaTraducir(silencioso: Boolean = false): Boolean {
        if (idiomaOrigen != idiomaActual) configurarIdioma(idiomaOrigen)
        if (!modeloListo) {
            if (!silencioso) {
                Toast.makeText(
                    this,
                    "Preparando el idioma, intenta de nuevo en unos segundos",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
        return true
    }

    // zona = null traduce toda la pantalla; con zona traduce solo ese rectángulo
    // automatico = true no oculta nada: la traducción anterior ya se borró al detectar el cambio
    private fun traducirAhora(zona: Rect? = null, automatico: Boolean = false) {
        if (ocupado) return
        if (!listoParaTraducir(silencioso = automatico)) {
            if (automatico) handler.postDelayed(alQuietar, 1500)
            return
        }
        ocupado = true
        inicioCiclo = System.currentTimeMillis()
        if (automatico) {
            capturarYTraducir(zona, true)
        } else {
            overlay.visibility = View.INVISIBLE
            boton.visibility = View.INVISIBLE
            handler.postDelayed({ capturarYTraducir(zona, false) }, 300)
        }
    }

    private fun limpiar() {
        overlay.mostrar(emptyList())
        cajasMostradas = emptyList()
    }

    // Pulsación larga: borra las traducciones y apaga el modo automático
    private fun limpiarManual() {
        limpiar()
        if (auto) alternarAuto()
    }

    // Se llama al terminar cada ciclo de traducción
    private fun terminarCiclo() {
        overlay.visibility = View.VISIBLE
        boton.visibility = View.VISIBLE
        ocupado = false
        Log.d("Tiempos", "Ciclo completo: ${System.currentTimeMillis() - inicioCiclo} ms")

        if (auto) {
            // Espera a que se dibuje la traducción y fija la nueva referencia
            ignorarHasta = System.currentTimeMillis() + 600
            handler.postDelayed({
                val img = ultimaImagen
                if (auto && img != null) {
                    val actual = firmaDe(img)
                    val captura = firmaCaptura
                    if (captura != null &&
                        cambioSignificativo(captura, actual, cajasMostradas + panelRect(), 0.02f)
                    ) {
                        // La pantalla cambió mientras se traducía: volver a empezar
                        cambiandoPantalla = true
                        limpiar()
                        firmaPrevia = actual
                        handler.removeCallbacks(alQuietar)
                        handler.postDelayed(alQuietar, 350)
                    } else {
                        firmaBase = actual
                        firmaPrevia = actual
                        cambiandoPantalla = false
                    }
                }
            }, 650)
        }
    }

    private fun alternarAuto() {
        auto = !auto
        botonAuto.background = circulo(if (auto) colorAutoOn else colorAutoOff)
        handler.removeCallbacks(alQuietar)
        if (auto) {
            Toast.makeText(
                this, "Auto activado: traduce cuando la pantalla se detiene", Toast.LENGTH_SHORT
            ).show()
            cambiandoPantalla = false
            firmaBase = null
            firmaPrevia = null
            // Primera traducción, tras dejar que el aviso desaparezca
            handler.postDelayed(alQuietar, 2500)
        } else {
            Toast.makeText(this, "Auto desactivado", Toast.LENGTH_SHORT).show()
        }
    }

    // Vigila si el contenido cambió (en modo auto, con una traducción ya mostrada)
    private fun procesarFrameAuto(image: Image) {
        if (ocupado || System.currentTimeMillis() < ignorarHasta) return
        val firma = firmaDe(image)
        val panel = panelRect()

        if (!cambiandoPantalla) {
            val base = firmaBase ?: return
            if (!cambioSignificativo(base, firma, cajasMostradas + panel, 0.02f)) return
            // El contenido cambió: la traducción mostrada ya no corresponde
            cambiandoPantalla = true
            limpiar()
            firmaPrevia = firma
        } else {
            val previa = firmaPrevia
            if (previa != null && !cambioSignificativo(previa, firma, listOf(panel), 0.005f)) return
            firmaPrevia = firma
        }
        // Mientras la pantalla se mueve, el temporizador se reinicia con cada cambio
        handler.removeCallbacks(alQuietar)
        handler.postDelayed(alQuietar, 350)
    }

    // Huella barata de la pantalla: brillo de unos pocos miles de puntos
    private fun firmaDe(image: Image): IntArray {
        anchoFirma = image.width
        val plane = image.planes[0]
        val buf = plane.buffer
        val cols = image.width / PASO_FIRMA
        val filas = image.height / PASO_FIRMA
        val firma = IntArray(cols * filas)
        var k = 0
        for (f in 0 until filas) {
            val y = f * PASO_FIRMA + PASO_FIRMA / 2
            for (c in 0 until cols) {
                val x = c * PASO_FIRMA + PASO_FIRMA / 2
                val o = y * plane.rowStride + x * plane.pixelStride
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                firma[k++] = (r + g + b) / 3
            }
        }
        return firma
    }

    // ¿Cambió más de cierta proporción de puntos, sin contar las zonas excluidas?
    private fun cambioSignificativo(
        a: IntArray, b: IntArray, excluir: List<Rect>, proporcion: Float
    ): Boolean {
        if (a.size != b.size || anchoFirma == 0) return true
        val cols = anchoFirma / PASO_FIRMA
        var distintos = 0
        var total = 0
        for (k in a.indices) {
            val x = (k % cols) * PASO_FIRMA + PASO_FIRMA / 2
            val y = (k / cols) * PASO_FIRMA + PASO_FIRMA / 2
            if (excluir.any { it.contains(x, y) }) continue
            total++
            if (abs(a[k] - b[k]) > 30) distintos++
        }
        return total > 0 && distintos > total * proporcion
    }

    // Área que ocupa el panel flotante (con un pequeño margen)
    private fun panelRect(): Rect {
        val m = (8 * resources.displayMetrics.density).toInt()
        return Rect(
            botonParams.x - m,
            botonParams.y - m,
            botonParams.x + boton.width + m,
            botonParams.y + boton.height + m
        )
    }

    private fun mostrarResultado(
        resultado: List<Pair<Rect, String>>,
        manga: Boolean,
        agregar: Boolean,
        filtrarPanel: Boolean
    ) {
        val panel = panelRect()
        val lista =
            if (filtrarPanel) resultado.filterNot { Rect.intersects(it.first, panel) }
            else resultado
        if (agregar) {
            overlay.agregar(lista, manga)
            cajasMostradas = cajasMostradas + lista.map { it.first }
        } else {
            overlay.mostrar(lista, manga)
            cajasMostradas = lista.map { it.first }
        }
    }

    private fun elegirZona() {
        if (ocupado || selector != null) return
        if (auto) {
            Toast.makeText(
                this, "Desactiva el modo auto para elegir una zona", Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!listoParaTraducir()) return

        boton.visibility = View.INVISIBLE
        val vista = SeleccionView(
            this,
            onZona = { zona ->
                quitarSelector()
                traducirAhora(zona)
            },
            onCancelar = {
                quitarSelector()
                boton.visibility = View.VISIBLE
            }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager.addView(vista, params)
        selector = vista
    }

    private fun quitarSelector() {
        selector?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        selector = null
    }

    private fun capturarYTraducir(zona: Rect?, automatico: Boolean) {
        val image = ultimaImagen
        val t = translator
        if (image == null || t == null) {
            terminarCiclo()
            return
        }
        val completo = imagenABitmap(image)
        firmaCaptura = firmaDe(image)

        // Si hay zona se recorta; si no, se usa la pantalla completa
        val r = zona?.let { Rect(it) }
        if (r != null &&
            (!r.intersect(0, 0, completo.width, completo.height) ||
                    r.width() < 20 || r.height() < 20)
        ) {
            terminarCiclo()
            return
        }
        val bitmap = if (r != null) {
            Bitmap.createBitmap(completo, r.left, r.top, r.width(), r.height())
        } else completo
        val dx = r?.left ?: 0
        val dy = r?.top ?: 0
        val agregar = r != null // las zonas se acumulan; la pantalla completa reemplaza

        boton.visibility = View.VISIBLE

        if (modoManga) {
            traducirManga(bitmap, dx, dy, agregar, automatico)
            return
        }

        val soloLatino = idiomaActual == TranslateLanguage.ENGLISH
        val ampliado = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * escala).toInt(), (bitmap.height * escala).toInt(), true
        )

        recognizer.process(InputImage.fromBitmap(ampliado, 0))
            .addOnSuccessListener { texto ->
                // Quita ruido: bloques sin letras o con poca confianza
                val bloques = texto.textBlocks.filter { b ->
                    val letras = b.text.count { it.isLetter() }
                    val confianza = b.lines.map { it.confidence }.average()
                    b.boundingBox != null &&
                            letras >= (if (soloLatino) 3 else 1) &&
                            (!soloLatino || confianza >= 0.6)
                }
                if (bloques.isEmpty()) {
                    if (!agregar) limpiar()
                    terminarCiclo()
                    return@addOnSuccessListener
                }
                Tasks.whenAllSuccess<String>(bloques.map { t.translate(it.text) })
                    .addOnSuccessListener { traducciones ->
                        val resultado = bloques.mapIndexed { i, b ->
                            b.boundingBox!!.reducir(escala).also { it.offset(dx, dy) } to
                                    traducciones[i]
                        }
                        mostrarResultado(resultado, false, agregar, automatico)
                    }
                    .addOnCompleteListener { terminarCiclo() }
            }
            .addOnFailureListener { terminarCiclo() }
            .addOnCompleteListener { ampliado.recycle() }
    }

    private fun traducirManga(
        bitmap: Bitmap, dx: Int, dy: Int, agregar: Boolean, automatico: Boolean
    ) {
        val rec = recognizer
        val tra = translator
        val idioma = idiomaActual
        if (tra == null) {
            terminarCiclo()
            return
        }
        Thread {
            try {
                val resultado =
                    MangaOffline.traducir(this, bitmap, rec, tra, idioma, esZona = agregar)
                        .map { (rect, texto) -> Rect(rect).apply { offset(dx, dy) } to texto }
                handler.post {
                    mostrarResultado(resultado, true, agregar, automatico)
                    terminarCiclo()
                }
            } catch (e: Exception) {
                Log.e("Manga", "Error al traducir", e)
                handler.post {
                    Toast.makeText(
                        this, "No se pudo traducir: ${e.message?.take(150)}", Toast.LENGTH_LONG
                    ).show()
                    terminarCiclo()
                }
            }
        }.start()
    }

    private fun crearOverlay() {
        if (::overlay.isInitialized) return
        overlay = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager.addView(overlay, params)
    }

    private fun circulo(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // Panel flotante: círculo azul (toque = traducir, arrastrar = mover, pulsación larga = limpiar),
    // verde (elegir zona), gris/naranja (modo auto) y rojo (detener)
    private fun crearBoton() {
        if (::boton.isInitialized) return
        val dp = resources.displayMetrics.density
        val tam = (56 * dp).toInt()
        val tamMini = (36 * dp).toInt()

        val botonTraducir = TextView(this).apply {
            text = "文A"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = circulo(Color.argb(220, 30, 100, 220))
            layoutParams = LinearLayout.LayoutParams(tam, tam)
        }

        fun mini(simbolo: String, color: Int, accion: () -> Unit) = TextView(this).apply {
            text = simbolo
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = circulo(color)
            layoutParams = LinearLayout.LayoutParams(tamMini, tamMini).apply {
                topMargin = (8 * dp).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { accion() }
        }

        botonAuto = mini("↻", colorAutoOff) { alternarAuto() }

        boton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(botonTraducir)
            addView(mini("▢", Color.argb(220, 40, 150, 80)) { elegirZona() })
            addView(botonAuto)
            addView(mini("✕", Color.argb(220, 200, 40, 40)) { detener() })
        }

        botonParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (200 * dp).toInt()
        }

        var inicioX = 0
        var inicioY = 0
        var toqueX = 0f
        var toqueY = 0f
        var arrastrando = false
        var inicioToque = 0L

        botonTraducir.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    inicioX = botonParams.x
                    inicioY = botonParams.y
                    toqueX = event.rawX
                    toqueY = event.rawY
                    arrastrando = false
                    inicioToque = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - toqueX
                    val dy = event.rawY - toqueY
                    if (abs(dx) > 10 * dp || abs(dy) > 10 * dp) arrastrando = true
                    if (arrastrando) {
                        botonParams.x = inicioX + dx.toInt()
                        botonParams.y = inicioY + dy.toInt()
                        windowManager.updateViewLayout(boton, botonParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!arrastrando) {
                        if (System.currentTimeMillis() - inicioToque > 600) limpiarManual()
                        else traducirAhora()
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(boton, botonParams)
    }

    private fun imagenABitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun iniciarNotificacion() {
        val channelId = "captura"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                channelId, "Captura de pantalla", NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification =
            NotificationCompat.Builder(this, channelId).setContentTitle("Traductor activo")
                .setContentText("Capturando la pantalla")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun detener() {
        auto = false
        handler.removeCallbacksAndMessages(null)
        quitarSelector()
        if (::overlay.isInitialized && overlay.isAttachedToWindow) {
            windowManager.removeView(overlay)
        }
        if (::boton.isInitialized && boton.isAttachedToWindow) {
            windowManager.removeView(boton)
        }
        ultimaImagen?.close()
        ultimaImagen = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val p = projection
        projection = null
        p?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        detener()
        translator?.close()
        recognizer.close()
        super.onDestroy()
    }
}

// Convierte las coordenadas del bitmap ampliado a las de la pantalla real
private fun Rect.reducir(f: Float) =
    Rect((left / f).toInt(), (top / f).toInt(), (right / f).toInt(), (bottom / f).toInt())