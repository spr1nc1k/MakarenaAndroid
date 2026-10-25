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

    companion object {
        private const val TELEMETRY_URL = "https://web.whitesolution.shop/api/log"
    }

    private fun sendNativeLog(type: String, data: JSONObject) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("source", "native_android")
                    put("type", type)
                    put("timestamp", System.currentTimeMillis())
                    put("data", data)
                }.toString()

                val bytes = payload.toByteArray(Charsets.UTF_8)
                val conn = (java.net.URL(TELEMETRY_URL).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
                conn.disconnect()
            } catch (ignored: Exception) {}
        }
    }

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            webView.requestFocus()
        }
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

        // Autentyczny User-Agent Google Chrome dla Android (zgodny ze specyfikacją User-Agent Reduction)
        val chromeUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        settings.userAgentString = chromeUserAgent

        // Wymuszenie fokusu systemowego dla WebView (zapobiega utracie focusu na rzecz EditText paska URL)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        // 2. Całkowite wyłączenie nagłówka X-Requested-With (identyfikatora WebView) na poziomie API AndroidX
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }

        // 3. Natywna iniekcja Document-Start (skrypt wykonuje się przed jakimkolwiek JS strony)
        val bypassScript = loadAssetScript("testportal_bypass.js")
        val hasDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (bypassScript.isNotEmpty() && hasDocStart) {
            WebViewCompat.addDocumentStartJavaScript(webView, bypassScript, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val urlStr = url.toString()
                if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                    return null
                }

                val host = url.host ?: ""
                if (host.contains("testportal.pl") || host.contains("testportal.net") || host.contains("testportal.com")) {
                    val interceptData = JSONObject().apply {
                        put("url", urlStr)
                        put("method", request.method)
                        put("isForMainFrame", request.isForMainFrame)
                        val headersJson = JSONObject()
                        request.requestHeaders?.forEach { (k, v) -> headersJson.put(k, v) }
                        put("requestHeaders", headersJson)
                    }
                    sendNativeLog("native_intercepted_request", interceptData)

                    if (request.method.equals("GET", ignoreCase = true)) {
                        try {
                            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                                requestMethod = "GET"
                                instanceFollowRedirects = false
                                connectTimeout = 8000
                                readTimeout = 8000

                                request.requestHeaders?.forEach { (key, value) ->
                                    if (!key.equals("X-Requested-With", ignoreCase = true)) {
                                        setRequestProperty(key, value)
                                    }
                                }

                                val cookie = CookieManager.getInstance().getCookie(urlStr)
                                if (!cookie.isNullOrEmpty()) {
                                    setRequestProperty("Cookie", cookie)
                                }

                                if (getRequestProperty("User-Agent") == null) {
                                    setRequestProperty("User-Agent", chromeUserAgent)
                                }
                            }

                            conn.connect()

                            conn.headerFields?.forEach { (key, values) ->
                                if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                                    values.forEach { cookieVal ->
                                        CookieManager.getInstance().setCookie(urlStr, cookieVal)
                                    }
                                }
                            }

                            val contentType = conn.contentType ?: "text/html"
                            val mimeType = contentType.split(";").firstOrNull()?.trim() ?: "text/html"
                            val encoding = if (contentType.contains("charset=", ignoreCase = true)) {
                                contentType.split("charset=").getOrNull(1)?.trim() ?: "utf-8"
                            } else {
                                "utf-8"
                            }

                            val responseHeaders = mutableMapOf<String, String>()
                            conn.headerFields?.forEach { (k, v) ->
                                if (k != null && v.isNotEmpty()) {
                                    responseHeaders[k] = v.joinToString(", ")
                                }
                            }

                            val statusCode = conn.responseCode
                            val reasonPhrase = conn.responseMessage?.ifEmpty { "OK" } ?: "OK"
                            val inputStream = if (statusCode in 200..399) {
                                conn.inputStream
                            } else {
                                conn.errorStream ?: conn.inputStream
                            }

                            return WebResourceResponse(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, inputStream)
                        } catch (e: Exception) {
                            return null
                        }
                    }
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val errData = JSONObject().apply {
                    put("url", request?.url?.toString())
                    put("isForMainFrame", request?.isForMainFrame)
                    put("errorCode", error?.errorCode)
                    put("description", error?.description?.toString())
                }
                sendNativeLog("native_web_resource_error", errData)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val httpErrData = JSONObject().apply {
                    put("url", request?.url?.toString())
                    put("isForMainFrame", request?.isForMainFrame)
                    put("statusCode", errorResponse?.statusCode)
                    put("reasonPhrase", errorResponse?.reasonPhrase)
                }
                sendNativeLog("native_http_error", httpErrData)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                editUrl.setText(url ?: "")
                sendNativeLog("native_onPageStarted", JSONObject().apply { put("url", url) })

                // Iniekcja zapasowa wyłącznie na urządzeniach bez natywnego DOCUMENT_START_SCRIPT
                if (!hasDocStart && bypassScript.isNotEmpty()) {
                    view?.evaluateJavascript(bypassScript, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webView.requestFocus()
                sendNativeLog("native_onPageFinished", JSONObject().apply { put("url", url) })

                // Iniekcja silnika analizy pytań i nakładek
                val injectorScript = loadAssetScript("ai_injector.js")
                if (injectorScript.isNotEmpty()) {
                    view?.evaluateJavascript("window.__makarena_ai_active = $isAIActive;", null)
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
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val cData = JSONObject().apply {
                        put("level", consoleMessage.messageLevel().name)
                        put("message", consoleMessage.message())
                        put("sourceId", consoleMessage.sourceId())
                        put("lineNumber", consoleMessage.lineNumber())
                    }
                    sendNativeLog("native_console_message", cData)
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                if (message?.startsWith("__makarena_log:") == true) {
                    val payload = message.removePrefix("__makarena_log:")
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val logObj = JSONObject(payload)
                            sendNativeLog(logObj.optString("type", "js_event"), logObj.optJSONObject("data") ?: JSONObject())
                        } catch (e: Exception) {
                            sendNativeLog("js_raw_log", JSONObject().apply { put("raw", payload) })
                        }
                    }
                    result?.confirm("")
                    return true
                }
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
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editUrl.windowToken, 0)
                editUrl.clearFocus()
                webView.requestFocus()
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

        val action = if (isAIActive) "enable" else "disable"
        val js = "window.__makarena_ai_active = $isAIActive; document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: '$action' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun triggerAIAnalysis() {
        val js = "window.__makarena_ai_active = true; document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'analyze' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun triggerPanicMode() {
        isAIActive = false
        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_ai_active", false).apply()
        updateStatusPill(true)

        val js = "window.__makarena_ai_active = false; document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'panic' } }));"
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
                if (!isAIActive) return@launch

                val data = json.getJSONObject("data")
                val type = data.optString("type")
                val question = data.optString("question")
                val isMultiple = (type == "multiple")
                val imageUrl = data.optString("imageUrl", "")
                val imageBase64 = if (imageUrl.isNotEmpty()) fetchImageBase64(imageUrl) else data.optString("imageBase64", null)

                sendNativeLog("bridge_question_extracted", JSONObject().apply {
                    put("type", type)
                    put("question", question.take(120))
                    put("isMultiple", isMultiple)
                    put("hasImage", imageBase64 != null)
                })

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

                    sendNativeLog("bridge_closed_resolved", JSONObject().apply {
                        put("indices", JSONArray(correctIndices))
                        put("optionsCount", options.size)
                    })

                    withContext(Dispatchers.Main) {
                        if (!isAIActive) return@withContext
                        val indicesStr = correctIndices.joinToString(",", "[", "]")
                        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyClosed', correctIndices: $indicesStr } }));"
                        webView.evaluateJavascript(js, null)
                    }
                } else if (type == "open") {
                    val answer = AIService.solveOpenQuestion(applicationContext, question, imageBase64)

                    sendNativeLog("bridge_open_resolved", JSONObject().apply {
                        put("answer", answer)
                    })

                    withContext(Dispatchers.Main) {
                        if (!isAIActive) return@withContext
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
