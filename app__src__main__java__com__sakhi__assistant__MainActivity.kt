package com.sakhi.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        val FILL = arrayOf(
            "youtube", "यूट्यूब", "open", "kholo", "खोलो", "खोलिए", "खोल", "दो", "करो", "चलाओ", "चला",
            "play", "search", "सर्च", "पर", "में", "को", "का", "की", "के", "please", "मुझे", "बताओ",
            "maps", "map", "मैप", "मैप्स", "navigate", "news", "google", "गूगल", "kar", "do", "par",
            "me", "ko", "ka", "ki", "ke", "है", "से", "जाओ", "सुनाओ", "लगाओ", "call", "कॉल", "phone",
            "फोन", "फ़ोन", "dhundo", "ढूंढो", "खोजो", "dikhao", "दिखाओ", "route", "directions", "रास्ता",
            "nearby", "dial", "डायल", "kahan", "कहाँ", "कहां", "ऐप", "app", "एप"
        )
        val APP_ALIAS = mapOf(
            "व्हाट्सएप" to "whatsapp", "वॉट्सऐप" to "whatsapp", "व्हाट्सऐप" to "whatsapp",
            "यूट्यूब" to "youtube", "कैमरा" to "camera", "गैलरी" to "gallery", "सेटिंग्स" to "settings",
            "सेटिंग" to "settings", "क्रोम" to "chrome", "फोन" to "phone", "इंस्टाग्राम" to "instagram",
            "फेसबुक" to "facebook", "जीमेल" to "gmail", "कैलकुलेटर" to "calculator", "घड़ी" to "clock",
            "गूगल" to "google", "मैप्स" to "maps", "फोटो" to "photos", "टेलीग्राम" to "telegram"
        )
    }

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var handsFree = false
    private var oneShot = false
    private var hindiMissing = false
    private var askedOverlay = false
    private var askedBattery = false
    private val history = ArrayList<Pair<String, String>>()
    private val main = Handler(Looper.getMainLooper())

    private var logBox: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var statusView: TextView? = null
    private var orb: Button? = null

    private val teal = Color.parseColor("#0B6B63")
    private val amber = Color.parseColor("#D98E04")
    private val bg = Color.parseColor("#E9F1EF")
    private val ink = Color.parseColor("#112B29")

    private val user: String get() = prefs.getString("user", "") ?: ""
    private val bot: String get() = prefs.getString("bot", "सखी") ?: "सखी"

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sakhi", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        val wake = intent?.getBooleanExtra("wake", false) == true
        if (user.isBlank()) showSetup(true)
        else if (wake) { showMain(null); wakeGreet() }
        else showMain(greeting() + " " + user + "! मैं आपकी केयर करती हूँ, आप अच्छे रहो। बताइए, क्या करूँ?")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("wake", false) == true && user.isNotBlank() && logBox != null) wakeGreet()
    }

    override fun onResume() {
        super.onResume()
        ListenService.paused = true
        ensureAlwaysOn()
    }

    override fun onPause() {
        super.onPause()
        ListenService.paused = false
    }

    private fun wakeGreet() {
        intent?.removeExtra("wake")
        oneShot = true
        say("बोलिए $user, मुझे क्या करना है?")
    }

    /** Starts the background listener when the "always listen" switch is on, asking for the needed settings once. */
    private fun ensureAlwaysOn() {
        if (user.isBlank() || !prefs.getBoolean("always", false)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true
            Toast.makeText(this, "\"दूसरे ऐप के ऊपर दिखाएँ\" चालू कीजिए, तभी नाम पुकारने पर मैं खुल पाऊँगी", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        val pw = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pw.isIgnoringBatteryOptimizations(packageName) && !askedBattery) {
            askedBattery = true
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        try { ListenService.start(this) } catch (e: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        val r = t.setLanguage(Locale.forLanguageTag("hi-IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Hindi voice is not installed: never fall back to an English voice, ask to install Hindi instead.
            hindiMissing = true
            Toast.makeText(
                this,
                "फोन में हिंदी आवाज़ नहीं है। अभी खुलने वाली स्क्रीन में हिंदी आवाज़ डाउनलोड कीजिए, फिर ऐप दोबारा खोलिए।",
                Toast.LENGTH_LONG
            ).show()
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e: Exception) {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e2: Exception) {}
            }
        } else {
            try {
                val hv = t.voices.filter { it.locale.language == "hi" }
                val pick = hv.firstOrNull { it.name.contains("hia") || it.name.contains("hic") || it.name.contains("female") }
                    ?: hv.firstOrNull()
                if (pick != null) t.voice = pick
            } catch (e: Exception) {}
        }
        t.setPitch(1.1f)
        t.setSpeechRate(0.95f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onSpeechDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onSpeechDone() }
        })
        ttsReady = true
        pendingSpeak?.let { speak(it); pendingSpeak = null }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recognizer?.destroy() } catch (e: Exception) {}
        tts?.stop()
        tts?.shutdown()
    }

    // ------------------------------------------------------------ small ui helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun label(s: String, size: Float, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(ink)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun edit(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.setTextColor(ink)
        e.background = rounded(Color.WHITE, 12)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(6)
        p.bottomMargin = dp(10)
        e.layoutParams = p
        return e
    }

    private fun pill(s: String): Button {
        val b = Button(this)
        b.text = s
        b.setTextColor(Color.WHITE)
        b.background = rounded(teal, 12)
        b.isAllCaps = false
        return b
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Good night"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            h < 21 -> "Good evening"
            else -> "Good night"
        }
    }

    // ------------------------------------------------------------ screens

    private fun showSetup(first: Boolean) {
        val sv = ScrollView(this)
        sv.setBackgroundColor(bg)
        val col = vbox()
        col.setPadding(dp(20), dp(48), dp(20), dp(24))
        sv.addView(col)

        col.addView(label(if (first) "नमस्ते! मैं आपकी अपनी सखी हूँ" else "सेटिंग", 24f, true))
        val intro = label(
            if (first) "बस अपना नाम बता दीजिए। मैं हमेशा इसी नाम से बुलाऊँगी." else "यहाँ से नाम और Gemini key बदल सकते हैं.",
            15f
        )
        intro.setPadding(0, dp(6), 0, dp(18))
        col.addView(intro)

        col.addView(label("आपका नाम", 14f, true))
        val nameIn = edit("जैसे: प्रिया", user)
        col.addView(nameIn)

        col.addView(label("मेरा नाम", 14f, true))
        val botIn = edit("सखी", if (user.isBlank()) "सखी" else bot)
        col.addView(botIn)

        col.addView(label("Gemini API key (aistudio.google.com से मुफ़्त मिलती है)", 14f, true))
        val keyIn = edit("AIza...", prefs.getString("key", "") ?: "")
        keyIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        col.addView(keyIn)

        col.addView(label("Gemini model", 14f, true))
        val modelIn = edit(DEFAULT_MODEL, prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        col.addView(modelIn)

        val alwaysSw = Switch(this)
        alwaysSw.text = "हमेशा सुनो: ऐप बंद हो तब भी नाम पुकारने पर जागूँ"
        alwaysSw.setTextColor(ink)
        alwaysSw.isChecked = prefs.getBoolean("always", false)
        alwaysSw.setPadding(0, dp(4), 0, dp(16))
        col.addView(alwaysSw)

        val save = pill("सेव करें")
        save.setOnClickListener {
            val n = nameIn.text.toString().trim()
            if (n.isEmpty()) {
                nameIn.error = "नाम लिखिए"
                return@setOnClickListener
            }
            prefs.edit()
                .putString("user", n)
                .putString("bot", botIn.text.toString().trim().ifEmpty { "सखी" })
                .putString("key", keyIn.text.toString().trim())
                .putString("model", modelIn.text.toString().trim().ifEmpty { DEFAULT_MODEL })
                .putBoolean("always", alwaysSw.isChecked)
                .apply()
            if (!alwaysSw.isChecked) ListenService.stop(this)
            showMain(if (first) "$n, मैं आपकी केयर करती हूँ, आप अच्छे रहो।" else null)
            ensureAlwaysOn()
        }
        col.addView(save)
        setContentView(sv)
    }

    private fun showMain(welcome: String?) {
        val root = vbox()
        root.setBackgroundColor(bg)
        root.setPadding(dp(16), dp(36), dp(16), dp(12))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = label(bot, 20f, true)
        top.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gear = label("⚙ सेटिंग", 14f)
        gear.setPadding(dp(10), dp(8), dp(10), dp(8))
        gear.setOnClickListener { stopListening(); showSetup(false) }
        top.addView(gear)
        root.addView(top)

        val hi = label(greeting() + ", " + user, 26f, true)
        hi.gravity = Gravity.CENTER
        hi.setPadding(0, dp(14), 0, dp(4))
        root.addView(hi)

        val o = Button(this)
        o.text = "🎤"
        o.textSize = 40f
        o.setTextColor(Color.WHITE)
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(teal)
        o.background = oval
        val op = LinearLayout.LayoutParams(dp(120), dp(120))
        op.gravity = Gravity.CENTER_HORIZONTAL
        op.topMargin = dp(12)
        o.setOnClickListener { if (listening) stopListening() else startListening() }
        root.addView(o, op)
        orb = o

        val st = label("", 14f)
        st.gravity = Gravity.CENTER
        st.setPadding(0, dp(8), 0, dp(8))
        root.addView(st)
        statusView = st

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val input = EditText(this)
        input.hint = "या यहाँ लिखिए..."
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setTextColor(ink)
        input.background = rounded(Color.WHITE, 12)
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val send = pill("भेजें")
        val sendAction = {
            val v = input.text.toString()
            input.setText("")
            handle(v)
        }
        send.setOnClickListener { sendAction() }
        input.setOnEditorActionListener { _, _, _ -> sendAction(); true }
        val sp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sp.leftMargin = dp(8)
        row.addView(send, sp)
        root.addView(row)

        val sw = Switch(this)
        sw.text = "लगातार सुनो (हैंड्स-फ्री)"
        sw.setTextColor(ink)
        sw.setPadding(0, dp(10), 0, dp(6))
        sw.setOnCheckedChangeListener { _, on ->
            handsFree = on
            if (on && !listening) startListening()
            if (!on) stopListening()
        }
        root.addView(sw)

        val sv = ScrollView(this)
        val box = vbox()
        sv.addView(box)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        logBox = box
        scroll = sv

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS
                ),
                1
            )
        }
        if (welcome != null) say(welcome)
    }

    private fun setStatus(s: String) { statusView?.text = s }

    private fun renderOrb() {
        val o = orb ?: return
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(if (listening) amber else teal)
        o.background = oval
        o.text = if (listening) "⏹" else "🎤"
        setStatus(if (listening) "सुन रही हूँ... बोलिए" else "")
    }

    private fun addBubble(text: String, mine: Boolean) {
        val box = logBox ?: return
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(ink)
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.background = rounded(if (mine) Color.parseColor("#D4E6E2") else Color.WHITE, 14)
        t.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.gravity = if (mine) Gravity.END else Gravity.START
        p.topMargin = dp(8)
        box.addView(t, p)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ speech out / in

    private fun speak(text: String) {
        if (!ttsReady) { pendingSpeak = text; return }
        if (hindiMissing) return
        tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "sakhi")
    }

    private fun onSpeechDone() {
        main.post {
            if ((handsFree || oneShot) && !listening) {
                oneShot = false
                startListening()
            }
        }
    }

    private fun say(text: String) {
        addBubble(text, false)
        history.add("model" to text)
        speak(text)
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            setStatus("माइक की इजाज़त दीजिए, फिर दोबारा दबाइए")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("इस फोन में आवाज़ पहचान नहीं मिली। Google ऐप अपडेट करें या लिखकर पूछें।")
            return
        }
        tts?.stop()
        try { recognizer?.destroy() } catch (e: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) setStatus(p)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                listening = false
                renderOrb()
                if (handsFree && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    main.postDelayed({ if (handsFree && !listening) startListening() }, 600)
                } else {
                    setStatus("सुनाई नहीं दिया। माइक फिर से दबाइए।")
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                renderOrb()
                val s = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!s.isNullOrBlank()) handle(s)
                else if (handsFree) main.postDelayed({ if (handsFree && !listening) startListening() }, 600)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        listening = true
        renderOrb()
        r.startListening(i)
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.stopListening() } catch (e: Exception) {}
        renderOrb()
    }

    // ------------------------------------------------------------ command handling

    private fun has(t: String, vararg k: String): Boolean = k.any { t.contains(it) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun strip(t: String, vararg extra: String): String {
        var r = " $t "
        for (x in FILL + extra) {
            r = r.replace(Regex("(?i)(?<![\\p{L}\\p{M}\\p{N}])" + Regex.escape(x) + "(?![\\p{L}\\p{M}\\p{N}])"), " ")
        }
        return r.replace(Regex("\\s+"), " ").trim()
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) {
            Toast.makeText(this, "लिंक नहीं खुल पाया", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findApp(name: String): Intent? {
        var n = name.lowercase(Locale.ROOT).trim()
        if (n.isEmpty()) return null
        for ((k, v) in APP_ALIAS) {
            if (n.contains(k)) { n = v; break }
        }
        val pm = packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(li, 0)
        val exact = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == n }
        val part = exact ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(n) }
        return part?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    private fun lookupNumber(name: String): String? {
        if (name.isBlank()) return null
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return null
        c.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private fun handle(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        addBubble(text, true)
        history.add("user" to text)
        while (history.size > 14) history.removeAt(0)
        val t = text.lowercase(Locale.ROOT)
        val reply = try {
            local(t, text)
        } catch (e: Exception) {
            "यह काम मुझसे नहीं हो पाया।"
        }
        when {
            reply == null -> askGemini("")
            reply.isEmpty() -> {}
            else -> say(reply)
        }
    }

    /** Returns a reply to speak, "" when someone else will answer, or null to ask Gemini. */
    private fun local(t: String, original: String): String? {
        val u = user

        if (has(t, "good morning", "गुड मॉर्निंग", "गुड मॉर्निग", "सुप्रभात"))
            return "Good morning $u! आपका दिन बहुत अच्छा हो। मैं आपकी केयर करती हूँ, आप अच्छे रहो।"
        if (has(t, "good afternoon", "गुड आफ्टरनून"))
            return "Good afternoon $u! खाना खाया? अपना ध्यान रखिए, मैं आपकी केयर करती हूँ।"
        if (has(t, "good evening", "गुड इवनिंग"))
            return "Good evening $u! दिन कैसा रहा? मैं आपकी केयर करती हूँ, आप अच्छे रहो।"
        if (has(t, "good night", "गुड नाइट", "शुभ रात्रि"))
            return "Good night $u. आराम से सोइए। मैं आपकी केयर करती हूँ, आप अच्छे रहो।"
        if (Regex("^(hi|hello|hey|namaste|नमस्ते|हैलो|हेलो)\\b").containsMatchIn(t))
            return greeting() + " $u! बताइए, मैं क्या करूँ?"
        if (has(t, "कैसी हो", "कैसे हो", "kaisi ho", "how are you"))
            return "मैं बिल्कुल ठीक हूँ $u। आप कैसे हैं? मैं आपकी केयर करती हूँ, आप अच्छे रहो।"

        if (t.length < 16 && has(t, "रुको", "चुप", "stop", "बस करो")) {
            tts?.stop()
            return ""
        }

        if (has(t, "कितने बजे", "टाइम क्या", "time kya", "what time", "समय क्या", "टाइम बताओ", "samay"))
            return "अभी " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date()) + " हुए हैं।"
        if (has(t, "तारीख", "आज की date", "what date", "आज क्या दिन", "today's date", "आज की डेट"))
            return "आज " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.forLanguageTag("hi-IN")).format(Date()) + " है।"
        if (has(t, "battery", "बैटरी")) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return "फोन की बैटरी " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " प्रतिशत है।"
        }

        if (has(t, "torch", "flashlight", "टॉर्च", "फ्लैशलाइट", "फ़्लैशलाइट", "टार्च")) {
            val off = has(t, "off", "बंद", "band", "बुझा")
            return try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], !off)
                if (off) "टॉर्च बंद कर दी।" else "टॉर्च चालू कर दी।"
            } catch (e: Exception) {
                "टॉर्च नहीं चल पाई।"
            }
        }

        if (has(t, "alarm", "अलार्म")) {
            val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(t)
            if (m == null) {
                startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                return "अलार्म की लिस्ट खोल दी। समय बोलकर कहिए, जैसे सुबह 6 बजे का अलार्म लगाओ।"
            }
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val pm = Regex("\\bp\\.?m\\.?\\b").containsMatchIn(t) || has(t, "शाम", "रात", "दोपहर")
            if (pm && h < 12) h += 12
            if (h > 23 || min > 59) return "समय समझ नहीं आया। फिर से बोलिए।"
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, bot)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            startActivity(i)
            return "ठीक है, " + String.format(Locale.ENGLISH, "%02d:%02d", h, min) + " का अलार्म लगा दिया।"
        }

        if (has(t, "timer", "टाइमर")) {
            val m = Regex("(\\d+)").find(t) ?: return "कितने मिनट का टाइमर लगाऊँ?"
            val n = m.groupValues[1].toInt()
            val secs = when {
                has(t, "second", "सेकंड", "सैकंड", "सेकेंड") -> n
                has(t, "hour", "घंटे", "घंटा") -> n * 3600
                else -> n * 60
            }
            startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
            return "टाइमर लगा दिया।"
        }

        if (has(t, "wifi", "wi-fi", "वाईफाई", "वाई-फाई", "वाई फाई")) {
            val a = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
            startActivity(Intent(a))
            return "वाई-फाई का पैनल खोल दिया। एंड्रॉयड के नियम की वजह से चालू या बंद आपको खुद दबाना होगा।"
        }
        if (has(t, "bluetooth", "ब्लूटूथ")) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return "ब्लूटूथ की सेटिंग खोल दी। वहाँ से चालू या बंद कर लीजिए।"
        }

        if (has(t, "volume", "वॉल्यूम", "वोल्यूम", "आवाज", "awaaz", "awaz")) {
            val up = has(t, "बढ़ा", "बढा", "badha", " up", "increase", "ज्यादा", "zyada", "तेज")
            val down = has(t, "कम", "kam", "down", "decrease", "low", "धीमे", "धीमा")
            if (up || down) {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                repeat(3) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                return if (up) "आवाज़ बढ़ा दी।" else "आवाज़ कम कर दी।"
            }
        }

        if (has(t, "call", "कॉल", "फोन करो", "फ़ोन करो", "phone karo", "dial", "डायल")) {
            val digits = t.filter { it in '0'..'9' }
            val name = strip(t)
            val number = if (digits.length >= 5) digits else lookupNumber(name)
            if (number == null) {
                return if (name.isBlank()) "किसे कॉल करना है? नाम या नंबर बोलिए."
                else if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                    "कॉन्टैक्ट देखने की इजाज़त नहीं है। फोन की सेटिंग में ऐप की इजाज़त दीजिए।"
                else "$name नाम का नंबर मुझे नहीं मिला।"
            }
            val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
            return if (digits.length >= 5) "$number पर कॉल लगा रही हूँ।" else "$name को कॉल लगा रही हूँ।"
        }

        if (has(t, "news", "न्यूज", "न्यूज़", "खबर", "ख़बर", "समाचार")) {
            val key = prefs.getString("key", "") ?: ""
            if (key.isBlank()) {
                openUrl("https://news.google.com/?hl=hi&gl=IN&ceid=IN:hi")
                return "Gemini key नहीं है, इसलिए Google News खोल दिया।"
            }
            askGemini("Google search से आज की ताज़ा मुख्य खबरें देखकर 5 सबसे ज़रूरी खबरें हर एक 1 छोटे वाक्य में बताओ।")
            return ""
        }

        if (has(t, "youtube", "यूट्यूब")) {
            val q = strip(t)
            if (q.isEmpty()) {
                val app = findApp("youtube")
                if (app != null) startActivity(app) else openUrl("https://m.youtube.com/")
                return "YouTube खोल रही हूँ।"
            }
            openUrl("https://www.youtube.com/results?search_query=" + enc(q))
            return "YouTube पर $q ढूँढ रही हूँ।"
        }

        if (has(t, "map", "मैप", "navigate", "रास्ता", "directions", "nearby", "नज़दीक", "पास में", "kahan hai", "कहाँ है", "कहां है")) {
            val q = strip(t)
            if (q.isEmpty()) {
                val app = findApp("maps")
                if (app != null) startActivity(app) else openUrl("https://www.google.com/maps")
                return "Maps खोल रही हूँ।"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc(q))))
            } catch (e: Exception) {
                openUrl("https://www.google.com/maps/search/" + enc(q))
            }
            return "Maps में $q दिखा रही हूँ।"
        }

        if (has(t, "open", "kholo", "खोलो", "खोलिए", "खोल दो", "खोल")) {
            val target = strip(t)
            val app = findApp(target)
            if (app != null) {
                startActivity(app)
                return "ठीक है, $target खोल रही हूँ।"
            }
            if (target.isNotEmpty() && target.split(" ").size <= 3 && has(t, "kholo", "खोलो", "खोलिए", "खोल दो"))
                return "$target नाम का ऐप मुझे नहीं मिला।"
        }

        return null
    }

    // ------------------------------------------------------------ Gemini

    private fun askGemini(hint: String) {
        val key = prefs.getString("key", "") ?: ""
        val lastUser = history.lastOrNull { it.first == "user" }?.second ?: ""
        if (key.isBlank()) {
            say("AI से जवाब पाने के लिए सेटिंग में Gemini key डालिए। अभी मैं Google पर खोज देती हूँ।")
            openUrl("https://www.google.com/search?q=" + enc(lastUser))
            return
        }
        val model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        val hist = ArrayList(history.takeLast(10))
        setStatus("सोच रही हूँ...")
        thread {
            val ans = try {
                callGemini(key, model, hist, hint)
            } catch (e: Exception) {
                "इंटरनेट या key में दिक्कत है।"
            }
            main.post {
                setStatus("")
                say(ans)
            }
        }
    }

    private fun callGemini(key: String, model: String, hist: List<Pair<String, String>>, hint: String): String {
        val contents = JSONArray()
        hist.forEachIndexed { i, pair ->
            val role = pair.first
            val tx = if (i == hist.lastIndex && role == "user" && hint.isNotEmpty()) pair.second + "\n(" + hint + ")" else pair.second
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", tx)))
            )
        }
        val sys = "तुम '$bot' हो, $user की अपनी महिला वॉइस असिस्टेंट। " +
            "जवाब 2 से 5 छोटे वाक्यों में दो, जो बोलने में आसान हों। कोई markdown, तारे, बुलेट, लिस्ट नंबर या इमोजी मत लगाओ। " +
            "यूज़र जिस भाषा में पूछे उसी में जवाब दो, हिंदी हो तो देवनागरी में। " +
            "ताज़ा जानकारी (खबर, स्कोर, मौसम, दाम) के लिए Google search का इस्तेमाल करो। " +
            "दवाई, सेहत या कानून के सवाल पर आम जानकारी दो और डॉक्टर या वकील से मिलने की सलाह दो।"

        fun post(grounded: Boolean): Pair<Int, String> {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", contents)
            if (grounded) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val s = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, s)
        }

        var res = post(true)
        if (res.first !in 200..299) res = post(false)
        if (res.first !in 200..299) {
            val msg = try {
                JSONObject(res.second).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                "कोड " + res.first
            }
            return "Gemini ने जवाब नहीं दिया: " + msg.take(140)
        }
        return try {
            val parts = JSONObject(res.second).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
            sb.toString().replace(Regex("[*#`]"), "").trim().ifEmpty { "जवाब खाली आया, दोबारा पूछिए।" }
        } catch (e: Exception) {
            "जवाब पढ़ नहीं पाई, दोबारा पूछिए।"
        }
    }
}
