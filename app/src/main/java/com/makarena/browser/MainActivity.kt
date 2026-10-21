package com.makarena.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusPill: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var editUrl: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var gestureStrip: View

    private var isAIActive = false
    private var volumeDownTimestamps = mutableListOf<Long>()
    private var volumeUpTimestamps = mutableListOf<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupListeners()

        // Załaduj stronę domyślną
        webView.loadUrl("https://www.testportal.pl")
    }

    private fun initViews() {
        webView = findViewById(R.id.web_view)
        progressBar = findViewById(R.id.loading_progress)
        statusPill = findViewById(R.id.status_pill)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        editUrl = findViewById(R.id.edit_url)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnSettings = findViewById(R.id.btn_settings)
        gestureStrip = findViewById(R.id.gesture_strip)

        // Odczyt zapisanego stanu asystenta z SharedPreferences
        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        isAIActive = prefs.getBoolean("is_ai_active", false)
        updateStatusPill(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                editUrl.setText(url ?: "")

                // 1. Iniekcja bypass na początku dokumentu
                val bypassScript = loadAssetScript("testportal_bypass.js")
                webView.evaluateJavascript(bypassScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                // 2. Iniekcja ai_injector.js
                val injectorScript = loadAssetScript("ai_injector.js")
                webView.evaluateJavascript(injectorScript, null)

                // Jeśli asystent jest włączony (tryb przełącznika), automatycznie analizujemy nowe pytanie
                if (isAIActive) {
                    mainHandler.postDelayed({
                        if (isAIActive) {
                            triggerAIAnalysis()
                        }
                    }, 400)
                }
            }
        }

        // Stealth IPC przez onJsPrompt (brak fingerprintów addJavascriptInterface)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                if (message?.startsWith("__makarena_bridge:") == true) {
                    val payload = message.removePrefix("__makarena_bridge:")
                    handleBridgePayload(payload)
                    result?.confirm("")
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                var url = editUrl.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://www.google.com/search?q=$url"
                }
                webView.loadUrl(url)
                true
            } else false
        }

        // Podwójne kliknięcie dolnego paska jako alternatywny przełącznik
        var lastGestureTap = 0L
        gestureStrip.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastGestureTap < 400) {
                toggleAI()
            }
            lastGestureTap = now
        }
    }

    // Intercepcja fizycznych klawiszy głośności (2x ciszej = WŁĄCZ/WYŁĄCZ, 2x głośniej = Panic Mode)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val now = System.currentTimeMillis()

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            cleanTimestamps(volumeDownTimestamps, now)
            volumeDownTimestamps.add(now)
            if (volumeDownTimestamps.size >= 2) {
                volumeDownTimestamps.clear()
                toggleAI()
            }
            return true // blokuje systemowe OSD głośności
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            cleanTimestamps(volumeUpTimestamps, now)
            volumeUpTimestamps.add(now)
            if (volumeUpTimestamps.size >= 2) {
                volumeUpTimestamps.clear()
                triggerPanicMode()
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun cleanTimestamps(list: MutableList<Long>, now: Long) {
        list.removeAll { now - it > 1200 }
    }

    private fun toggleAI() {
        if (isAIActive) {
            triggerPanicMode()
        } else {
            triggerAIAnalysis()
        }
    }

    private fun triggerAIAnalysis() {
        isAIActive = true
        saveAIState(true)
        updateStatusPill(true)

        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'analyze' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun triggerPanicMode() {
        isAIActive = false
        saveAIState(false)
        updateStatusPill(false)

        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'panic' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun saveAIState(active: Boolean) {
        getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_ai_active", active)
            .apply()
    }

    private fun updateStatusPill(isTemporaryAlert: Boolean) {
        statusPill.visibility = View.VISIBLE
        if (isAIActive) {
            statusDot.setBackgroundColor(getColor(R.color.neon_green))
            statusText.text = getString(R.string.ai_active)
        } else {
            statusDot.setBackgroundColor(getColor(R.color.neon_red))
            statusText.text = getString(R.string.panic_mode)
        }

        if (isTemporaryAlert) {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                if (!isAIActive) {
                    statusPill.visibility = View.GONE
                }
            }, 1800)
        }
    }

    private fun handleBridgePayload(payload: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val json = JSONObject(payload)
                if (json.optString("action") != "questionDataExtracted") return@launch
                val data = json.getJSONObject("data")
                val type = data.optString("type")
                val question = data.optString("question")
                val isMultiple = (type == "multiple")
                val imageUrl = data.optString("imageUrl").takeIf { it.isNotEmpty() }

                // Pobranie grafiki jeśli występuje w pytaniu
                var imageBase64: String? = null
                if (imageUrl != null) {
                    imageBase64 = fetchImageBase64(imageUrl)
                }

                if (type == "closed" || type == "multiple") {
                    val optsJson = data.getJSONArray("options")
                    val options = mutableListOf<String>()
                    for (i in 0 until optsJson.length()) {
                        options.add(optsJson.getJSONObject(i).optString("text"))
                    }

                    val correctIndices = AIService.solveClosedQuestion(
                        applicationContext,
                        question,
                        options,
                        isMultiple,
                        imageBase64
                    )

                    withContext(Dispatchers.Main) {
                        val indicesStr = correctIndices.joinToString(",", "[", "]")
                        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyClosed', correctIndices: $indicesStr } }));"
                        webView.evaluateJavascript(js, null)
                    }
                } else if (type == "open") {
                    val answer = AIService.solveOpenQuestion(applicationContext, question, imageBase64)
                    withContext(Dispatchers.Main) {
                        val escaped = answer.replace("\"", "\\\"").replace("\n", " ")
                        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyOpen', answerText: \"$escaped\" } }));"
                        webView.evaluateJavascript(js, null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchImageBase64(urlStr: String): String? {
        return try {
            val url = java.net.URL(urlStr)
            val bytes = url.openStream().use { it.readBytes() }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadAssetScript(filename: String): String {
        return try {
            assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
