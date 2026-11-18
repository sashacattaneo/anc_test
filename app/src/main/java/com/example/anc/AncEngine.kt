package com.example.anc

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Cancellazione attiva del rumore con algoritmo FxLMS a retroazione (una sola entrata microfonica),
 * come nel progetto iancraz/ANC-Implementation:
 *   1) calibrazione: misura il ritardo dell'anello altoparlante->microfono (correlazione) e stima
 *      il percorso secondario S(z) con NLMS e rumore bianco ("offline mode");
 *   2) FxLMS: x(n) = e(n) - S^(z) y(n) viene filtrato con S^ e usato per aggiornare W(z).
 */
class AncEngine(
    private val onStatus: (String) -> Unit,
    private val onLevel: (Double) -> Unit
) {
    companion object {
        const val FS = 16000
        const val B = 64           // campioni per blocco
        const val NS = 512         // taps di S^ (32 ms) dopo il ritardo
        const val NW = 256         // taps del filtro adattivo
        const val MAXD = 3200      // ritardo massimo cercato: 200 ms
        const val MAX_OUT = 0.5f   // limite di sicurezza sull'uscita
    }

    /** Buffer circolare: get(j) = campione di j passi fa (j=0 = ultimo inserito). */
    private class Hist(val n: Int) {
        val b = FloatArray(2 * n)
        var p = 0
        fun push(v: Float) { p = (p - 1 + n) % n; b[p] = v; b[p + n] = v }
        fun get(j: Int) = b[p + j]
        fun dotAt(c: FloatArray, off: Int, count: Int): Float {
            var s = 0f
            for (j in 0 until count) s += c[j] * b[p + off + j]
            return s
        }
        fun powerAt(off: Int, count: Int): Float {
            var s = 0f
            for (j in 0 until count) { val v = b[p + off + j]; s += v * v }
            return s
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var mu = 0.01f
    @Volatile var sHat: FloatArray? = null
        private set
    @Volatile private var delay = 1

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, AudioTrack>? {
        val minR = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val minT = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        var rec: AudioRecord? = null
        for (src in intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )) {
            try {
                val r = AudioRecord(
                    src, FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                    max(minR, B * 8)
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
            } catch (_: Exception) { }
        }
        if (rec == null) return null
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(FS)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minT, B * 8))
            .build()
        return Pair(rec, trk)
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    /** Ritardo (in campioni) con massima correlazione tra uscita y e microfono e. */
    private fun estimateDelay(ya: FloatArray, ea: FloatArray): Pair<Int, Double> {
        val n = ya.size
        var best = 0
        var bv = 0.0
        for (lag in 0..MAXD) {
            var acc = 0.0
            for (k in MAXD until n) acc += ea[k].toDouble() * ya[k - lag]
            if (abs(acc) > bv) { bv = abs(acc); best = lag }
        }
        var ey = 0.0
        var ee = 0.0
        for (k in MAXD until n) { ey += ya[k].toDouble() * ya[k]; ee += ea[k].toDouble() * ea[k] }
        return Pair(best, bv / sqrt(ey * ee + 1e-12))
    }

    /** Fase 1: misura ritardo e percorso secondario (7 s). */
    fun calibrate() {
        if (running) return
        running = true
        thread = Thread {
            val io = open()
            if (io == null) { onStatus("Impossibile aprire microfono/altoparlante"); running = false; return@Thread }
            val (rec, trk) = io
            try {
                val s = FloatArray(NS)
                val yh = Hist(MAXD + NS + 2)
                val y = FloatArray(B)
                val e = FloatArray(B)
                val total = FS * 7 / B
                val phaseA = FS * 3 / 2 / B
                val ya = FloatArray(phaseA * B)
                val ea = FloatArray(phaseA * B)
                var d = 1
                var nc = 0.0
                var errPow = 0.0
                var micPow = 0.0
                trk.play(); rec.startRecording()
                for (blk in 0 until total) {
                    if (!running) break
                    for (i in 0 until B) y[i] = (Random.nextFloat() * 2f - 1f) * 0.25f
                    trk.write(y, 0, B, AudioTrack.WRITE_BLOCKING)
                    rec.read(e, 0, B, AudioRecord.READ_BLOCKING)
                    if (blk < phaseA) {
                        System.arraycopy(y, 0, ya, blk * B, B)
                        System.arraycopy(e, 0, ea, blk * B, B)
                        for (i in 0 until B) yh.push(y[i])
                        if (blk == phaseA - 1) {
                            onStatus("Misuro il ritardo...")
                            val (lag, c) = estimateDelay(ya, ea)
                            nc = c
                            d = max(1, lag - 16)
                        }
                    } else {
                        for (i in 0 until B) {
                            yh.push(y[i])
                            val err = e[i] - yh.dotAt(s, d, NS)
                            val g = 0.5f * err / (yh.powerAt(d, NS) + 1e-6f)
                            for (j in 0 until NS) s[j] += g * yh.get(d + j)
                            if (blk > total * 3 / 4) { errPow += err * err; micPow += e[i] * e[i] }
                        }
                    }
                    if (blk % 25 == 0) onStatus("Calibrazione... ${blk * 100 / total}%")
                }
                if (running) {
                    var peak = 0
                    var pv = 0f
                    for (j in 0 until NS) if (abs(s[j]) > pv) { pv = abs(s[j]); peak = j }
                    val resid = 10 * log10((errPow + 1e-12) / (micPow + 1e-12))
                    val delayMs = (d + peak) * 1000.0 / FS
                    val diag = "(ritardo %.0f ms, correlazione %.2f, residuo %.0f dB)".format(delayMs, nc, resid)
                    if (nc < 0.05) {
                        onStatus("Calibrazione fallita: il microfono non sente il rumore riprodotto $diag.\n" +
                            "Alza il volume multimediale, avvicina il microfono all'altoparlante, " +
                            "non usare Bluetooth.")
                    } else if (resid > -5) {
                        onStatus("Calibrazione fallita: percorso non stimabile $diag.\n" +
                            "Riduci il rumore ambiente, alza il volume, prova con cuffie cablate.")
                    } else {
                        sHat = s
                        delay = d
                        onStatus("Calibrazione OK $diag." +
                            if (delayMs > 40) "\nRitardo alto: l'ANC funzionerà solo su rumori periodici (motori, ronzii)." else "")
                    }
                }
            } finally {
                rec.stop(); trk.stop(); rec.release(); trk.release()
                running = false
            }
        }.also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    /** Fase 2: FxLMS a retroazione in tempo reale. */
    fun start() {
        val sh = sHat
        if (sh == null) { onStatus("Esegui prima la calibrazione"); return }
        if (running) return
        val d = delay
        running = true
        thread = Thread {
            val io = open()
            if (io == null) { onStatus("Impossibile aprire microfono/altoparlante"); running = false; return@Thread }
            val (rec, trk) = io
            try {
                val w = FloatArray(NW)
                val yh = Hist(d + NS + 1)
                val xh = Hist(d + NS + 1)
                val xf = Hist(NW)
                val e = FloatArray(B)
                val out = FloatArray(B)
                var ae = 1e-9
                var ad = 1e-9
                var blk = 0
                trk.play(); rec.startRecording()
                onStatus("ANC attivo")
                while (running) {
                    rec.read(e, 0, B, AudioRecord.READ_BLOCKING)
                    val m = mu
                    for (i in 0 until B) {
                        val ek = e[i]
                        // disturbo stimato: x(n) = e(n) - sum_j S^[j] y(n-d-j)
                        val dh = ek - yh.dotAt(sh, d - 1, NS)
                        xh.push(dh)
                        // filtered-x: x'(n) = sum_j S^[j] x(n-d-j)
                        xf.push(xh.dotAt(sh, d, NS))
                        // NLMS con leakage
                        val g = m * ek / (xf.powerAt(0, NW) + 1e-4f)
                        for (k in 0 until NW) w[k] = w[k] * 0.99999f - g * xf.get(k)
                        var yk = xh.dotAt(w, 0, NW)
                        if (yk.isNaN()) { java.util.Arrays.fill(w, 0f); yk = 0f }
                        yk = yk.coerceIn(-MAX_OUT, MAX_OUT)
                        yh.push(yk)
                        out[i] = yk
                        ae = 0.9995 * ae + 0.0005 * ek * ek
                        ad = 0.9995 * ad + 0.0005 * dh * dh
                    }
                    trk.write(out, 0, B, AudioTrack.WRITE_BLOCKING)
                    if (++blk % 20 == 0) onLevel(10 * log10(ae / ad))
                }
            } finally {
                rec.stop(); trk.stop(); rec.release(); trk.release()
                running = false
                onStatus("ANC fermato")
            }
        }.also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }
}
