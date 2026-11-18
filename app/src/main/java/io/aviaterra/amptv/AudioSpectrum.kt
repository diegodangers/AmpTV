package io.aviaterra.amptv

import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** Best-effort output-mix FFT probe. Session zero may be restricted by TV firmware. */
class AudioSpectrum {
    private var visualizer: Visualizer? = null
    @Volatile var bands: List<Float> = List(28) { 0f }
        private set
    @Volatile var status: String = "Not started"
        private set

    @Volatile var bandCount: Int = 28
    @Volatile var sensitivity: Float = 1f
    @Volatile var decay: Float = 0.65f
    @Volatile var showPeaks: Boolean = true
    @Volatile var peaks: List<Float> = List(64) { 0f }
        private set

    fun start() {
        stop()
        try {
            val v = Visualizer(0)
            val sizes = Visualizer.getCaptureSizeRange()
            v.captureSize = minOf(1024, sizes[1])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.size < 8) return
                    val count = fft.size / 2
                    val n = bandCount.coerceIn(19, 64)
                    val previous = bands
                    val values = (0 until n).map { index ->
                        val lower = max(1, (Math.pow(count.toDouble(), index / n.toDouble())).toInt())
                        val upper = max(lower + 1, (Math.pow(count.toDouble(), (index + 1) / n.toDouble())).toInt())
                        var peak = 0.0
                        for (bin in lower until minOf(upper, count)) {
                            val real = fft[bin * 2].toInt().toDouble()
                            val imag = fft[bin * 2 + 1].toInt().toDouble()
                            peak = maxOf(peak, hypot(real, imag))
                        }
                        val raw = ((ln(1.0 + peak) / ln(180.0)).toFloat() * sensitivity).coerceIn(0f, 1f)
                        maxOf(raw, (previous.getOrElse(index) { 0f } - (1f - decay.coerceIn(0f, 0.95f)) * 0.15f).coerceAtLeast(0f))
                    }
                    peaks = values.mapIndexed { index, value -> maxOf(value, (peaks.getOrElse(index) { 0f } - 0.025f).coerceAtLeast(0f)) }
                    bands = values
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            visualizer = v
            status = "Real FFT active"
        } catch (e: Exception) {
            status = "Real FFT unavailable: ${e.javaClass.simpleName}"
            stop()
        }
    }

    fun stop() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        bands = List(64) { 0f }
        peaks = List(64) { 0f }
    }
}
