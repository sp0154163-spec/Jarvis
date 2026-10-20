package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("j", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun field(h: String, k: String) = EditText(this).apply {
            hint = h
            setText(p.getString(k, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 28f
        }
        val g = field("Gemini API key", "gemini")
        val v = field("Picovoice AccessKey", "pico")
        val start = Button(this).apply { text = "START (Jarvis jagao)" }
        val stop = Button(this).apply { text = "STOP (band karo)" }
        val info = TextView(this).apply {
            text = "START dabao, phir bolo: \"Jarvis\". Sone ke liye: \"Bye Jarvis\"."
        }
        listOf(title, g, v, start, stop, info).forEach { root.addView(it) }
        setContentView(root)

        start.setOnClickListener {
            p.edit()
                .putString("gemini", g.text.toString().trim())
                .putString("pico", v.text.toString().trim())
                .apply()
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (need.isEmpty()) go() else requestPermissions(need.toTypedArray(), 1)
        }
        stop.setOnClickListener {
            stopService(Intent(this, JarvisService::class.java))
            Toast.makeText(this, "Jarvis band", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) go()
        else Toast.makeText(this, "Mic permission zaroori hai", Toast.LENGTH_LONG).show()
    }

    private fun go() {
        startForegroundService(Intent(this, JarvisService::class.java))
        Toast.makeText(this, "Jarvis chalu. Bolo: Jarvis", Toast.LENGTH_SHORT).show()
    }
}
