package com.example.tuner432

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Globalny przełącznik efektu hali (pogłos). */
object HallEffect {
    @Volatile var enabled = false
}

/**
 * Efekt "hali" - pogłos typu Freeverb (8 grzebieni + 4 allpass na kanał).
 * Działa na 16-bit stereo PCM, w łańcuchu obok 8D i przed Sonic (432).
 */
class HallReverbProcessor : BaseAudioProcessor() {

    private val combTun = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTun = intArrayOf(556, 441, 341, 225)
    private val stereo = 23
    private val feedback = 0.92f
    private val damp = 0.20f
    private val wet = 0.30f
    private val dry = 0.72f
    private val inputGain = 0.015f

    private lateinit var combL: Array<Comb>
    private lateinit var combR: Array<Comb>
    private lateinit var apL: Array<AllPass>
    private lateinit var apR: Array<AllPass>

    private class Comb(size: Int, val fb: Float, val damp: Float) {
        val buf = FloatArray(size); var idx = 0; var store = 0f
        fun process(input: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = input + store * fb
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class AllPass(size: Int) {
        val buf = FloatArray(size); var idx = 0
        fun process(input: Float): Float {
            val bufout = buf[idx]
            val out = -input + bufout
            buf[idx] = input + bufout * 0.5f
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT || f.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val scale = f.sampleRate / 44100.0
        fun sz(n: Int) = maxOf(1, (n * scale).toInt())
        combL = Array(combTun.size) { Comb(sz(combTun[it]), feedback, damp) }
        combR = Array(combTun.size) { Comb(sz(combTun[it] + stereo), feedback, damp) }
        apL = Array(apTun.size) { AllPass(sz(apTun[it])) }
        apR = Array(apTun.size) { AllPass(sz(apTun[it] + stereo)) }
        return f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = inputBuffer.remaining() / 4
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val on = HallEffect.enabled
        repeat(frames) {
            val l = inputBuffer.short
            val r = inputBuffer.short
            if (on) {
                val fL = l / 32768f
                val fR = r / 32768f
                val inL = fL * inputGain
                val inR = fR * inputGain
                var wL = 0f
                var wR = 0f
                for (c in combL) wL += c.process(inL)
                for (c in combR) wR += c.process(inR)
                for (a in apL) wL = a.process(wL)
                for (a in apR) wR = a.process(wR)
                val oL = (fL * dry + wL * wet).coerceIn(-1f, 1f)
                val oR = (fR * dry + wR * wet).coerceIn(-1f, 1f)
                out.putShort((oL * 32767f).toInt().toShort())
                out.putShort((oR * 32767f).toInt().toShort())
            } else {
                out.putShort(l)
                out.putShort(r)
            }
        }
        out.flip()
    }
}
