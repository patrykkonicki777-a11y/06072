package com.example.tuner432

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Współdzielone ID sesji audio (usługa -> wizualizator w UI). */
object AudioSession {
    @Volatile var id = 0
}

/**
 * Piasek cymatyczny (jak CymaScope): ziarna osiadają na węzłach radialnego pola
 * -> okrągła mandala (pierścienie + płatki). Trójwarstwowa poświata addytywna:
 * indygo -> błękit -> BIEL tam, gdzie piasek gęsty (linie węzłowe). Mody rosną z częstotliwością.
 */
class CymaticsView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val nb = 56
    private val bands = FloatArray(nb)

    private val p = 3800
    private val px = FloatArray(p)   // -1..1 (środek = 0)
    private val py = FloatArray(p)
    private val pts = FloatArray(p * 2)
    private val rnd = Random()
    private var peakSmooth = 0f
    private var phase = 0f

    private val bg = 0xFF07091A.toInt()
    private val clip = Path()

    private fun addPaint(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = argb
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val glowPaint = addPaint(0x285A50E6.toInt())   // indygo, szeroka poświata
    private val midPaint = addPaint(0x60708CFF.toInt())    // błękit
    private val corePaint = addPaint(0xA0C8DCFF.toInt())   // biało-błękitny rdzeń (gęsto -> biel)

    init {
        for (k in 0 until p) {
            px[k] = rnd.nextFloat() * 2f - 1f
            py[k] = rnd.nextFloat() * 2f - 1f
        }
    }

    fun updateFft(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < nb) return
        val per = bins / nb
        for (i in 0 until nb) {
            var m = 0f
            val start = i * per
            for (j in 0 until per) {
                val idx = (start + j) * 2
                if (idx + 1 < fft.size) {
                    val re = fft[idx].toFloat()
                    val im = fft[idx + 1].toFloat()
                    m += hypot(re, im)
                }
            }
            m /= per
            var v = (m / 90f).coerceIn(0f, 1f)
            v = sqrt(v)
            bands[i] = max(v, bands[i] * 0.86f)
        }
    }

    fun decayIdle() {
        for (i in 0 until nb) bands[i] *= 0.94f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val r = 28f
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        val side = min(w, h).toFloat()
        corePaint.strokeWidth = max(2f, side * 0.0060f)
        midPaint.strokeWidth = max(3f, side * 0.0115f)
        glowPaint.strokeWidth = max(6f, side * 0.024f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(bg)

        var sum = 0f; var peak = 0; var peakV = 0f
        val half = nb / 2
        for (i in 0 until nb) {
            sum += bands[i]
            if (i < half && bands[i] > peakV) { peakV = bands[i]; peak = i }
        }
        val energy = sum / nb
        peakSmooth += (peak - peakSmooth) * 0.15f
        val pf = (peakSmooth / half).coerceIn(0f, 1f)
        phase += 0.03f
        val bass = bands[0]
        val fast = kotlin.math.sin(phase)
        val fast2 = kotlin.math.sin(phase * 0.8f)

        // prawo Chladniego: liczba węzłów ~ sqrt(częstotliwości); mody żyją co klatkę
        val cpf = sqrt(pf)
        val af = 3f + 7f * cpf + 1.4f * fast + 3f * peakV      // węzły diametryczne (płatki)
        val nf = 1.5f + 3.5f * cpf + 0.7f * fast2 + 2f * bass  // węzły pierścieniowe
        val af2 = af + 4f
        val nf2 = nf + 1f
        val amp = (0.12f + 1.7f * energy).coerceIn(0f, 1f)

        val cx = w / 2f; val cy = h / 2f
        val rad = min(w, h) / 2f
        val ph = phase.toDouble()
        val pi = Math.PI
        val maxStep = 0.05f

        for (k in 0 until p) {
            var x = px[k]; var y = py[k]
            val r = hypot(x, y)
            val th = atan2(y, x).toDouble()
            val rr = r.toDouble()
            // radialny profil Bessela (asymptotyczny) -> realne pierścienie, zanik na zewnątrz
            val x1 = nf * pi * rr
            val x2 = nf2 * pi * rr
            val bes1 = Math.cos(x1 - 0.7853982) / Math.sqrt(x1 + 1.2)
            val bes2 = Math.cos(x2 - 0.7853982) / Math.sqrt(x2 + 1.2)
            // superpozycja dwóch modów (mode mixing)
            val f = bes1 * cos(af * th + ph) + 0.5 * bes2 * cos(af2 * th - ph)
            val vib = Math.abs(f).toFloat() * amp * 1.4f
            val step = vib * maxStep + 0.0007f
            x += (rnd.nextFloat() * 2f - 1f) * step
            y += (rnd.nextFloat() * 2f - 1f) * step
            // trzymaj w KWADRACIE (odbij od krawędzi)
            if (x < -1f) x = -2f - x else if (x > 1f) x = 2f - x
            if (y < -1f) y = -2f - y else if (y > 1f) y = 2f - y
            px[k] = x; py[k] = y
            pts[2 * k] = cx + x * rad
            pts[2 * k + 1] = cy + y * rad
        }

        canvas.drawPoints(pts, glowPaint)
        canvas.drawPoints(pts, midPaint)
        canvas.drawPoints(pts, corePaint)
        canvas.restore()

        postInvalidateOnAnimation()
    }
}
