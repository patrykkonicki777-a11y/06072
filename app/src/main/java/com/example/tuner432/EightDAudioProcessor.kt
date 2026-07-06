package com.example.tuner432

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Globalny przełącznik efektu 8D (usługa i UI są w tym samym procesie). */
object EightD {
    @Volatile var enabled = false
}

/**
 * Efekt "8D audio": powolny auto-panning lewo-prawo (dźwięk krąży wokół głowy).
 * Działa na 16-bit stereo PCM, PRZED Sonic (więc konwersja 432 Hz dalej działa).
 * Gdy wyłączony - przepuszcza dźwięk bez zmian.
 */
class EightDAudioProcessor : BaseAudioProcessor() {

    private var sampleRate = 44100
    private var phase = 0.0
    private var inc = 0.0
    private val freq = 0.15          // pełne okrążenie ~6.7 s
    private val depth = 0.9          // siła efektu (0..1)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET   // tylko stereo 16-bit
        }
        sampleRate = inputAudioFormat.sampleRate
        inc = 2.0 * PI * freq / sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = inputBuffer.remaining() / 4
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val on = EightD.enabled
        repeat(frames) {
            val l = inputBuffer.short
            val r = inputBuffer.short
            if (on) {
                phase += inc
                if (phase > 2 * PI) phase -= 2 * PI
                val pan = ((sin(phase) * 0.5) + 0.5) * (PI / 2)   // 0..π/2
                val dl = (1.0 - depth) + depth * cos(pan)
                val dr = (1.0 - depth) + depth * sin(pan)
                out.putShort((l.toDouble() * dl).toInt().coerceIn(-32768, 32767).toShort())
                out.putShort((r.toDouble() * dr).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                out.putShort(l)
                out.putShort(r)
            }
        }
        out.flip()
    }
}
