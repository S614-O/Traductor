package com.example.traductor

object EstiloTraduccion {
    @Volatile var fuente: String = "sans"   // "sans", "serif" o "mono"
    @Volatile var escalaTexto: Float = 1f   // 0.5 a 1.5
    @Volatile var negrita: Boolean = false
    @Volatile var tema: Int = 0             // 0 = auto, 1 = blanco, 2 = oscuro, 3 = amarillo, 4 = azul
    @Volatile var opacidad: Float = 0.92f   // 0.3 a 1
}