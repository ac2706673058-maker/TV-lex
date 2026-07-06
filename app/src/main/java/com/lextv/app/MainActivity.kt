package com.lextv.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import android.Manifest
import android.content.pm.PackageManager
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val voskModels = HashMap<String, Model>()
    private var speechService: SpeechService? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(0xFF0D111E.toInt())
        web.addJavascriptInterface(Bridge(), "Bridge")
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        setContentView(web)
        hideSystemUi()
        tts = TextToSpeech(this, this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        web.loadUrl("file:///android_asset/www/index.html")
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            fun bad(r: Int?) = r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED || r == null
            var r = tts?.setLanguage(Locale.US)
            if (bad(r)) r = tts?.setLanguage(Locale.UK)
            if (bad(r)) r = tts?.setLanguage(Locale.getDefault())
            if (bad(r)) { try { r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) } catch (_: Exception) {} }
            ttsReady = !bad(r)
        }
        runOnUiThread { web.evaluateJavascript("window.onTtsReady && window.onTtsReady($ttsReady)", null) }
    }

    private fun sendKey(name: String): Boolean {
        runOnUiThread { web.evaluateJavascript("window.onTvKey && window.onTvKey('$name')", null) }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> sendKey("UP")
            KeyEvent.KEYCODE_DPAD_DOWN -> sendKey("DOWN")
            KeyEvent.KEYCODE_DPAD_LEFT -> sendKey("LEFT")
            KeyEvent.KEYCODE_DPAD_RIGHT -> sendKey("RIGHT")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> sendKey("OK")
            KeyEvent.KEYCODE_BACK -> sendKey("BACK")
            KeyEvent.KEYCODE_MENU -> sendKey("MENU")
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> sendKey("PLAY")
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        try { speechService?.stop(); speechService?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }

    private val voskListener = object : RecognitionListener {
        private fun field(json: String?, key: String): String {
            return try { JSONObject(json ?: "{}").optString(key, "") } catch (e: Exception) { "" }
        }
        override fun onPartialResult(h: String?) {
            val t = field(h, "partial")
            if (t.isNotEmpty()) js("window.onVoicePart && window.onVoicePart(" + JSONObject.quote(t) + ")")
        }
        override fun onResult(h: String?) {
            val t = field(h, "text")
            if (t.isNotEmpty()) {
                stopListenInternal()
                js("window.onVoice && window.onVoice(" + JSONObject.quote(t) + ")")
            }
        }
        override fun onFinalResult(h: String?) { onResult(h) }
        override fun onError(e: Exception?) { stopListenInternal(); js("window.onVoiceErr && window.onVoiceErr(" + JSONObject.quote(e?.message ?: "error") + ")") }
        override fun onTimeout() { stopListenInternal(); js("window.onVoiceErr && window.onVoiceErr('timeout')") }
    }

    private fun stopListenInternal() {
        runOnUiThread { try { speechService?.stop(); speechService?.shutdown() } catch (_: Exception) {}; speechService = null }
    }

    inner class Bridge {

        @JavascriptInterface
        fun speak(text: String, rate: Float) {
            if (!ttsReady) return
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lex")
        }

        @JavascriptInterface
        fun stopSpeak() { tts?.stop() }

        @JavascriptInterface
        fun isTtsReady(): Boolean = ttsReady

        @JavascriptInterface
        fun save(key: String, json: String) {
            try { File(filesDir, "$key.json").writeText(json) } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun load(key: String): String {
            return try {
                val f = File(filesDir, "$key.json")
                if (f.exists()) f.readText() else ""
            } catch (_: Exception) { "" }
        }

        // 词库清单:内置assets/decks + 外部扩展目录(未来新增词书的口子)
        // 外部目录: /sdcard/Android/data/com.lextv.app/files/decks/*.json
        @JavascriptInterface
        fun getDecks(): String {
            val out = JSONArray()
            try {
                val mf = assets.open("decks/manifest.json").bufferedReader().readText()
                val arr = JSONObject(mf).getJSONArray("decks")
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    d.put("source", "asset")
                    out.put(d)
                }
            } catch (_: Exception) {}
            try {
                val ext = File(getExternalFilesDir(null), "decks")
                if (ext.exists()) {
                    ext.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                        val d = JSONObject()
                        d.put("id", "ext_" + f.nameWithoutExtension)
                        d.put("name", f.nameWithoutExtension)
                        d.put("icon", "\uD83D\uDCD8")
                        d.put("files", JSONArray().put(f.name))
                        d.put("source", "ext")
                        out.put(d)
                    }
                }
            } catch (_: Exception) {}
            return out.toString()
        }

        @JavascriptInterface
        fun readDeckFile(source: String, name: String): String {
            return try {
                if (source == "ext") File(File(getExternalFilesDir(null), "decks"), name).readText()
                else assets.open("decks/$name").bufferedReader().readText()
            } catch (_: Exception) { "[]" }
        }

        @JavascriptInterface
        fun hasVoice(): Boolean = true

        @JavascriptInterface
        fun startListen(lang: String) {
            runOnUiThread {
                if (speechService != null) return@runOnUiThread
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                    js("window.onVoiceErr && window.onVoiceErr('no-permission')"); return@runOnUiThread
                }
                val asset = if (lang == "cn") "model-cn" else "model-en"
                fun begin(m: Model) {
                    try {
                        val rec = Recognizer(m, 16000.0f)
                        speechService = SpeechService(rec, 16000.0f)
                        speechService?.startListening(voskListener)
                        js("window.onVoiceReady && window.onVoiceReady()")
                    } catch (e: Exception) { js("window.onVoiceErr && window.onVoiceErr(" + JSONObject.quote(e.message ?: "mic error") + ")") }
                }
                val cached = voskModels[asset]
                if (cached != null) { begin(cached) } else {
                    js("window.onVoicePart && window.onVoicePart('(\u9996\u6b21\u52a0\u8f7d\u8bc6\u522b\u6a21\u578b\u4e2d...)')")
                    StorageService.unpack(this@MainActivity, asset, asset,
                        { m -> voskModels[asset] = m; begin(m) },
                        { e -> js("window.onVoiceErr && window.onVoiceErr(" + JSONObject.quote(e.message ?: "model error") + ")") })
                }
            }
        }

        @JavascriptInterface
        fun stopListen() { stopListenInternal() }

        @JavascriptInterface
        fun aiChat(payload: String, cbId: String) {
            Thread {
                var out = "{}"
                try {
                    val c = URL("https://open.bigmodel.cn/api/paas/v4/chat/completions").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("Authorization", "Bearer d4559d711e5a44b2818b657f8729df45.HBFjJ0FthlA0dI4N")
                    c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
                    c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val st = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                    out = st.bufferedReader().readText()
                } catch (e: Exception) {
                    out = "{\"error\":{\"message\":\"" + (e.message ?: "network error") + "\"}}"
                }
                runOnUiThread { web.evaluateJavascript("window.onAiReply('" + cbId + "'," + JSONObject.quote(out) + ")", null) }
            }.start()
        }

        @JavascriptInterface
        fun exitApp() { runOnUiThread { finish() } }
    }
}
