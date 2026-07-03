package com.telo.tinyzora.util
object AudioUtils {
    fun recordAudio(onAmplitude: (Float) -> Unit, onMaxDurationReached: () -> Unit, stopSignal: () -> Boolean): ByteArray = ByteArray(0)
    fun summariseAmplitudes(amps: List<Float>): List<Float> = amps
}
