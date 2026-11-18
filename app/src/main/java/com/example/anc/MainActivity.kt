package com.example.anc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var level: TextView
    private lateinit var engine: AncEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = AncEngine(
            onStatus = { msg -> runOnUiThread { status.text = msg } },
            onLevel = { db -> runOnUiThread { level.text = "Riduzione stimata: %.1f dB".format(-db) } }
        )

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "ANC – Cancellazione attiva del rumore"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Usa cuffie con microfono vicino all'orecchio (cablate: latenza minore). " +
                "1) Calibra in un ambiente non troppo rumoroso con volume medio. " +
                "2) Avvia. Funziona meglio su rumori periodici a bassa frequenza (motori, ventole, ronzii)."
            setPadding(0, pad / 2, 0, pad)
        })

        val calib = Button(this).apply { text = "1. Calibra (6 s)" }
        val start = Button(this).apply { text = "2. Avvia ANC" }
        val stop = Button(this).apply { text = "Stop" }
        root.addView(calib); root.addView(start); root.addView(stop)

        root.addView(TextView(this).apply { text = "Intensità adattamento"; setPadding(0, pad, 0, 0) })
        val seek = SeekBar(this).apply { max = 100; progress = 30 }
        root.addView(seek)

        status = TextView(this).apply { text = "Pronto"; setPadding(0, pad, 0, 0) }
        level = TextView(this).apply { textSize = 18f; gravity = Gravity.START; setPadding(0, pad / 2, 0, 0) }
        root.addView(status); root.addView(level)
        setContentView(root)

        fun muFrom(p: Int) = 0.001f + (p / 100f) * 0.03f
        engine.mu = muFrom(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { engine.mu = muFrom(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        calib.setOnClickListener { withMic { engine.calibrate() } }
        start.setOnClickListener { withMic { engine.start() } }
        stop.setOnClickListener { engine.stop() }
    }

    private var pending: (() -> Unit)? = null

    private fun withMic(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) pending?.invoke()
        else status.text = "Permesso microfono negato"
        pending = null
    }

    override fun onStop() {
        super.onStop()
        engine.stop()
    }
}
