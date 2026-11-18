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
import kotlin.random.Random

/**
 * Cancellazione attiva del rumore con algoritmo FxLMS a retroazione (feedback ANC, una sola
 * entrata microfonica), come descritto nel progetto iancraz/ANC-Implementation:
 *   1) calibrazione offline del percorso secondario S(z) con NLMS e rumore bianco ("offline mode");
 *   2) FxLMS: il segnale di riferimento x(n) = e(n) - S^(z) y(n) viene ricostruito dal microfono
 *      d'errore, filtrato con S^ e usato per aggiornare il filtro adattivo W(z).
 */
class AncEngine(
    private val onStatus: (String) -> Unit,
    private val onLevel: (Double) -> Unit
) {
    companion object {
        const val FS = 16000
        const val B = 64          // campioni per blocco
        const val NS = 512        // taps del percorso secondario stimato (32 ms)
        const val NW = 256        // taps del filtro adattivo
        const val MAX_OUT = 0.5f  // limite di sicurezza sull'uscita
    }

    /** Buffer circolare "a doppia scrittura": get(j) = campione di j passi fa (j=0 = ultimo). */
    private class Hist(val n: Int) {
        val b = FloatArray(2 * n)
        var p = 0
        fun push(v: Float) { p = (p - 1 + n) % n; b[p] = v; b[p + n] = v }
        fun get(j: Int) = b[p + j]
        fun dot(c: FloatArray, from: Int, count: Int): Float {
            var s = 0f
            for (j in 0 until count) s += c[from + j] * b[p + j]
            return s
        }
        fun power(count: Int): Float {
            var s = 0f
            for (j in 0 until count) { val v = b[p + j]; s += v * v }
            return s
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var mu = 0.01f
    @Volatile var sHat: FloatArray? = null
        private set

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, AudioTrack>? {
        val minR = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val minT = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        var rec: AudioRecord? = null
        // Preferisce una sorgente senza elaborazioni (niente noise suppression di sistema)
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
        thread?.join(2000)
        thread = null
    }

    /** Fase 1: stima il percorso secondario altoparlante -> microfono con NLMS (6 s). */
    fun calibrate() {
        if (running) return
        running = true
        thread = Thread {
            val io = open()
            if (io == null) { onStatus("Impossibile aprire microfono/altoparlante"); running = false; return@Thread }
            val (rec, trk) = io
            try {
                val s = FloatArray(NS)
                val yh = Hist(NS)
                val y = FloatArray(B); val e = FloatArray(B)
                val total = FS * 6 / B
                var errPow = 0.0; var micPow = 0.0
                trk.play(); rec.startRecording()
                for (blk in 0 until total) {
                    if (!running) break
                    for (i in 0 until B) y[i] = (Random.nextFloat() * 2f - 1f) * 0.15f
                    trk.write(y, 0, B, AudioTrack.WRITE_BLOCKING)
                    rec.read(e, 0, B, AudioRecord.READ_BLOCKING)
                    for (i in 0 until B) {
                        yh.push(y[i])
                        val err = e[i] - yh.dot(s, 0, NS)
                        val g = 0.5f * err / (yh.power(NS) + 1e-6f)
                        for (j in 0 until NS) s[j] += g * yh.get(j)
                        if (blk > total * 3 / 4) { errPow += err * err; micPow += e[i] * e[i] }
                    }
                    if (blk % 25 == 0) onStatus("Calibrazione... ${blk * 100 / total}%")
                }
                if (running) {
                    var peak = 0; var pv = 0f
                    for (j in 0 until NS) if (abs(s[j]) > pv) { pv = abs(s[j]); peak = j }
                    val resid = 10 * log10((errPow + 1e-12) / (micPow + 1e-12))
                    val delayMs = peak * 1000.0 / FS
                    if (resid < -6 && pv > 1e-3) {
                        sHat = s
                        onStatus("Calibrazione OK. Ritardo anello: %.0f ms, errore residuo %.0f dB.%s".format(
                            delayMs, resid,
                            if (delayMs > 25) "\nAttenzione: ritardo alto, l'ANC funzionerà solo su rumori periodici (motori, ronzii)." else ""))
                    } else {
                        onStatus("Calibrazione fallita (residuo %.0f dB). Alza il volume media, riduci il rumore ambiente e riprova.".format(resid))
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
        running = true
        thread = Thread {
            val io = open()
            if (io == null) { onStatus("Impossibile aprire microfono/altoparlante"); running = false; return@Thread }
            val (rec, trk) = io
            try {
                val w = FloatArray(NW)
                val yh = Hist(NS); val xh = Hist(NS); val xf = Hist(NW)
                val e = FloatArray(B); val out = FloatArray(B)
                var ae = 1e-9; var ad = 1e-9
                var blk = 0
                trk.play(); rec.startRecording()
                onStatus("ANC attivo")
                while (running) {
                    rec.read(e, 0, B, AudioRecord.READ_BLOCKING)
                    val m = mu
                    for (i in 0 until B) {
                        val ek = e[i]
                        // riferimento: disturbo stimato  x(n) = e(n) - sum_{j>=1} S^[j] y(n-j)
                        val dh = ek - dotShift(sh, yh)
                        xh.push(dh)
                        // filtered-x: x'(n) = S^ * x
                        xf.push(xh.dot(sh, 0, NS))
                        // aggiornamento NLMS di W con leakage
                        val g = m * ek / (xf.power(NW) + 1e-4f)
                        for (k in 0 until NW) w[k] = w[k] * 0.99999f - g * xf.get(k)
                        // uscita anti-rumore
                        var yk = xh.dot(w, 0, NW)
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

    /** sum_{j=1}^{NS-1} S^[j] * y(n-j), dove yh.get(j-1) = y(n-j). */
    private fun dotShift(s: FloatArray, yh: Hist): Float {
        var acc = 0f
        for (j in 1 until NS) acc += s[j] * yh.get(j - 1)
        return acc
    }
}
