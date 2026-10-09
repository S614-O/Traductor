package com.example.traductor

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MotorTexto(val idioma: String) {

    val recognizer: TextRecognizer = when (idioma) {
        TranslateLanguage.CHINESE ->
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        TranslateLanguage.JAPANESE ->
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(idioma)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
    )

    // Descarga el modelo la primera vez (necesita internet); después funciona sin conexión
    fun prepararModelo() {
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
    }

    fun cerrar() {
        recognizer.close()
        translator.close()
    }
}