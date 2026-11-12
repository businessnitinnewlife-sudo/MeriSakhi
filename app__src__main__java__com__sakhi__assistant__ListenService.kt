package com.sakhi.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Background "wake word" listener. Runs as a foreground service (notification stays visible),
 * keeps the speech recognizer cycling, and when it hears the assistant's name it opens the app.
 */
class ListenService : Service() {

    companion object {
        @Volatile var paused = false
        const val CH = "sakhi_listen"

        fun start(c: Context) {
            c.startForegroundService(Intent(c, ListenService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ListenService::class.java))
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var alive = false
    private var busy = false
    private var startedAt = 0L
    private var cooldownUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("sakhi", Context.MODE_PRIVATE)
        val bot = prefs.getString("bot", "सखी") ?: "सखी"

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Sakhi सुन रही है", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("$bot सुन रही है")
            .setContentText("मेरा नाम पुकारिए, मैं जाग जाऊँगी")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!alive) {
            alive = true
            tick()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        h.removeCallbacksAndMessages(null)
        killRec()
        super.onDestroy()
    }

    /** Watchdog: runs every 1.5 s and (re)starts listening when allowed. */
    private fun tick() {
        if (!alive) return
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val now = SystemClock.elapsedRealtime()
        if (paused || !granted || now < cooldownUntil) {
            killRec()
        } else if (busy && now - startedAt > 20000) {
            killRec()
            begin()
        } else if (!busy) {
            begin()
        }
        h.postDelayed({ tick() }, 1500)
    }

    private fun killRec() {
        rec?.let {
            try { it.cancel() } catch (e: Exception) {}
            try { it.destroy() } catch (e: Exception) {}
        }
        rec = null
        busy = false
    }

    private fun restartSoon(delay: Long) {
        h.postDelayed({
            if (alive && !paused && !busy && SystemClock.elapsedRealtime() >= cooldownUntil) begin()
        }, delay)
    }

    private fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        killRec()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        busy = true
        startedAt = SystemClock.elapsedRealtime()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                check(partialResults)
            }
            override fun onResults(results: Bundle?) {
                busy = false
                if (!check(results)) restartSoon(300)
            }
            override fun onError(error: Int) {
                busy = false
                val slow = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT
                restartSoon(if (slow) 1500 else 400)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    private fun wakeNames(): List<String> {
        val bot = (getSharedPreferences("sakhi", Context.MODE_PRIVATE).getString("bot", "सखी") ?: "सखी")
            .lowercase(Locale.ROOT).trim()
        val out = ArrayList<String>()
        if (bot.isNotEmpty()) out.add(bot)
        if (bot == "सखी") { out.add("sakhi"); out.add("सखि"); out.add("sakhee") }
        return out
    }

    private fun check(b: Bundle?): Boolean {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val names = wakeNames()
        val hit = list.any { s ->
            val low = s.lowercase(Locale.ROOT)
            names.any { low.contains(it) }
        }
        if (hit) wake()
        return hit
    }

    private fun wake() {
        killRec()
        cooldownUntil = SystemClock.elapsedRealtime() + 6000
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        i.putExtra("wake", true)
        try { startActivity(i) } catch (e: Exception) {}
    }
}
