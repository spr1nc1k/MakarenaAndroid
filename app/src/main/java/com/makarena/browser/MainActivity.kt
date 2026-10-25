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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
        // 1. Zapewnienie pełnej obsługi ciasteczek sesyjnych i 3rd-party dla serwisów autoryzacyjnych
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Autentyczny User-Agent Google Chrome dla Android 14
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"

        // 2. Całkowite wyłączenie nagłówka X-Requested-With (identyfikatora WebView)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }

        // 3. Natywna iniekcja Document-Start (skrypt wykonuje się przed jakimkolwiek JS strony)
        val bypassScript = loadAssetScript("testportal_bypass.js")
        if (bypassScript.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bypassScript, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                editUrl.setText(url ?: "")

                // Zapasowa iniekcja przy starcie strony
                if (bypassScript.isNotEmpty()) {
                    view?.evaluateJavascript(bypassScript, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                // Iniekcja silnika analizy pytań i nakładek
                val injectorScript = loadAssetScript("ai_injector.js")
                if (injectorScript.isNotEmpty()) {
                    view?.evaluateJavascript(injectorScript, null)
                }

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
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress in 15..80 && bypassScript.isNotEmpty()) {
                    view?.evaluateJavascript(bypassScript, null)
                }
            }

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
            } else {
                false
            }
        }

        // Niewidoczny pasek gestów u dołu: potrójne kliknięcie jako alternatywny wyzwalacz AI
        gestureStrip.setOnClickListener(object : View.OnClickListener {
            private var lastTap = 0L
            private var tapCount = 0

            override fun onClick(v: View?) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 400) {
                    tapCount++
                    if (tapCount >= 2) { // 3 tapnięcia
                        tapCount = 0
                        toggleAIAssistant()
                    }
                } else {
                    tapCount = 0
                }
                lastTap = now
            }
        })
    }

    // Przechwytywanie klawiszy głośności:
    // 2x głośność w dół = WŁĄCZ / WYŁĄCZ (przełącznik permanentny)
    // 2x głośność w górę = PANIC MODE
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    volumeDownTimestamps.add(now)
                    volumeDownTimestamps.removeAll { now - it > 500 }
                    if (volumeDownTimestamps.size >= 2) {
                        volumeDownTimestamps.clear()
                        toggleAIAssistant()
                        return true
                    }
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    volumeUpTimestamps.add(now)
                    volumeUpTimestamps.removeAll { now - it > 500 }
                    if (volumeUpTimestamps.size >= 2) {
                        volumeUpTimestamps.clear()
                        triggerPanicMode()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleAIAssistant() {
        isAIActive = !isAIActive

        // Zapisanie trwałego stanu w SharedPreferences
        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_ai_active", isAIActive).apply()

        updateStatusPill(true)

        if (isAIActive) {
            triggerAIAnalysis()
        } else {
            triggerPanicMode()
        }
    }

    private fun triggerAIAnalysis() {
        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'analyze' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun triggerPanicMode() {
        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'panic' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun updateStatusPill(animate: Boolean) {
        if (isAIActive) {
            statusDot.setBackgroundResource(R.drawable.bg_circle_btn)
            statusText.text = "AI AKTYWNE"
            statusPill.alpha = 1f
        } else {
            statusText.text = "PANIC"
            statusPill.alpha = 0f
        }
    }

    private fun handleBridgePayload(payload: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(payload)
                val action = json.optString("action")
                if (action != "questionDataExtracted") return@launch

                val data = json.getJSONObject("data")
                val type = data.optString("type")
                val question = data.optString("question")
                val isMultiple = (type == "multiple")
                val imageUrl = data.optString("imageUrl", "")
                val imageBase64 = if (imageUrl.isNotEmpty()) fetchImageBase64(imageUrl) else data.optString("imageBase64", null)

                if (type == "closed" || type == "multiple") {
                    val optionsArr = data.getJSONArray("options")
                    val options = mutableListOf<String>()
                    for (i in 0 until optionsArr.length()) {
                        options.add(optionsArr.getJSONObject(i).optString("text"))
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
