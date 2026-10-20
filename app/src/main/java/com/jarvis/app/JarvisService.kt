package com.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val MODEL = "gemini-3.5-flash"
        const val SYSTEM = "Tum Jarvis ho, mere personal AI assistant. Hinglish mein " +
            "chhote, saaf jawab do (1-3 vakya), kyunki jawab bolke sunaya jayega. " +
            "Emojis ya markdown mat use karo."
    }

    private val main = Handler(Looper.getMainLooper())
    private var wake: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var rec: SpeechRecognizer? = null
    private val history = JSONArray()
    private var onSpoken: (() -> Unit)? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis chalu hai")
            .setContentText("\"Jarvis\" bolke jagao")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        tts?.language = Locale("hi", "IN")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { fireSpoken() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { fireSpoken() } }
        })
        startWake()
    }

    private fun fireSpoken() {
        val cb = onSpoken
        onSpoken = null
        cb?.invoke()
    }

    private fun speak(text: String, then: () -> Unit) {
        val t = tts
        if (t == null) { then(); return }
        onSpoken = then
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun startWake() {
        val key = getSharedPreferences("j", MODE_PRIVATE).getString("pico", "") ?: ""
        try {
            if (wake == null) {
                wake = PorcupineManager.Builder()
                    .setAccessKey(key)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .build(applicationContext, PorcupineManagerCallback { _ -> main.post { onWake() } })
            }
            wake?.start()
        } catch (e: Exception) {
            toast("Wake word error: " + e.message)
            stopSelf()
        }
    }

    private fun onWake() {
        try { wake?.stop() } catch (e: Exception) {}
        speak("Ji, boliye.") { listen() }
    }

    private fun listen() {
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) sleep() else handle(t)
            }
            override fun onError(e: Int) { sleep() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        rec?.startListening(i)
    }

    private fun handle(t: String) {
        val l = t.lowercase()
        if (l.contains("bye") || l.contains("बाय")) {
            speak("Theek hai, bye.") { sleep() }
            return
        }
        Thread {
            val a = gemini(t)
            main.post { speak(a) { listen() } }
        }.start()
    }

    private fun sleep() {
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
        try { wake?.start() } catch (e: Exception) { toast("Wake restart error: " + e.message) }
    }

    private fun slice(): JSONArray {
        val out = JSONArray()
        val from = maxOf(0, history.length() - 19)
        for (i in from until history.length()) out.put(history.get(i))
        return out
    }

    private fun part(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun gemini(q: String): String {
        val key = getSharedPreferences("j", MODE_PRIVATE).getString("gemini", "") ?: ""
        history.put(part("user", q))
        try {
            val body = JSONObject()
                .put("system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", slice())
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val txt = c.inputStream.bufferedReader().readText()
            val a = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            history.put(part("model", a))
            return a
        } catch (e: Exception) {
            history.remove(history.length() - 1)
            toast("Gemini error: " + e.message)
            return "Maaf kijiye, abhi jawab nahi de paaya."
        }
    }

    override fun onDestroy() {
        try { rec?.destroy() } catch (e: Exception) {}
        try { wake?.stop(); wake?.delete() } catch (e: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
