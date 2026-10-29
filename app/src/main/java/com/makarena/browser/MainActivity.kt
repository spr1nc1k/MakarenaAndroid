// Kotlin, Android, Makarena Browser
// Production stealth browser client for Testportal with physical key toggles and live telemetry

package com.makarena.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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

    private var currentSolveJob: kotlinx.coroutines.Job? = null
    private var lastSolvedSignature: String = ""
    private var lastSolvedTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupListeners()

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

        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        isAIActive = prefs.getBoolean("is_ai_active", false)
        updateStatusPill(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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

        val chromeUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        settings.userAgentString = chromeUserAgent

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }

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
                    }
                    TelemetryLogger.log("native_intercepted_request", interceptData)

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
                TelemetryLogger.log("native_web_resource_error", JSONObject().apply {
                    put("url", request?.url?.toString())
                    put("errorCode", error?.errorCode)
                    put("description", error?.description?.toString())
                })
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                editUrl.setText(url ?: "")
                TelemetryLogger.log("native_onPageStarted", JSONObject().apply { put("url", url) })

                if (!hasDocStart && bypassScript.isNotEmpty()) {
                    view?.evaluateJavascript(bypassScript, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webView.requestFocus()
                TelemetryLogger.log("native_onPageFinished", JSONObject().apply { put("url", url) })

                val injectorScript = loadAssetScript("ai_injector.js")
                if (injectorScript.isNotEmpty()) {
                    view?.evaluateJavascript(injectorScript, null)
                }

                if (isAIActive) {
                    mainHandler.postDelayed({
                        if (isAIActive) triggerAIAnalysis()
                    }, 350)
                    mainHandler.postDelayed({
                        if (isAIActive) triggerAIAnalysis()
                    }, 1100)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    TelemetryLogger.log("console_message", JSONObject().apply {
                        put("level", consoleMessage.messageLevel().name)
                        put("message", consoleMessage.message())
                        put("line", consoleMessage.lineNumber())
                    })
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
                    try {
                        val logObj = JSONObject(payload)
                        TelemetryLogger.log(logObj.optString("type", "js_event"), logObj.optJSONObject("data") ?: JSONObject(), "js_client")
                    } catch (e: Exception) {
                        TelemetryLogger.log("js_raw_log", JSONObject().apply { put("raw", payload) }, "js_client")
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

        gestureStrip.setOnClickListener(object : View.OnClickListener {
            private var lastTap = 0L
            private var tapCount = 0

            override fun onClick(v: View?) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 500) {
                    tapCount++
                    if (tapCount >= 2) {
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    volumeDownTimestamps.add(now)
                    volumeDownTimestamps.removeAll { now - it > 800 }
                    if (volumeDownTimestamps.size >= 2) {
                        volumeDownTimestamps.clear()
                        toggleAIAssistant()
                        return true
                    }
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    volumeUpTimestamps.add(now)
                    volumeUpTimestamps.removeAll { now - it > 800 }
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

        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_ai_active", isAIActive).apply()

        updateStatusPill(true)
        triggerHapticFeedback(isAIActive)

        Toast.makeText(
            this,
            if (isAIActive) "Makarena AI: WŁĄCZONE (Active)" else "Makarena AI: WYŁĄCZONE (Panic)",
            Toast.LENGTH_SHORT
        ).show()

        TelemetryLogger.log("native_ai_toggle", JSONObject().apply {
            put("isAIActive", isAIActive)
        })

        if (isAIActive) {
            triggerAIAnalysis()
        } else {
            triggerPanicMode()
        }
    }

    private fun triggerHapticFeedback(active: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(if (active) 60 else 180, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(if (active) 60 else 180, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(if (active) 60 else 180)
                }
            }
        } catch (_: Exception) {}
    }

    private fun triggerAIAnalysis() {
        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'analyze' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun triggerPanicMode() {
        isAIActive = false
        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_ai_active", false).apply()
        updateStatusPill(false)
        triggerHapticFeedback(false)

        val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'panic' } }));"
        webView.evaluateJavascript(js, null)

        TelemetryLogger.log("panic_mode_triggered", JSONObject())
    }

    private fun updateStatusPill(animate: Boolean) {
        if (isAIActive) {
            statusPill.visibility = View.VISIBLE
            statusPill.alpha = 1f
            statusDot.setBackgroundResource(R.drawable.bg_circle_btn)
            statusText.text = "AI AKTYWNE"
        } else {
            statusText.text = "PANIC"
            statusPill.alpha = 0f
            statusPill.visibility = View.GONE
        }
    }

    private fun handleBridgePayload(payload: String) {
        try {
            val json = JSONObject(payload)
            val action = json.optString("action")
            if (action != "questionDataExtracted") return

            val data = json.getJSONObject("data")
            val type = data.optString("type")
            val question = data.optString("question").trim()
            if (question.length < 3) return

            val isMultiple = (type == "multiple")
            val imageUrl = data.optString("imageUrl", "")
            val optionsArr = data.optJSONArray("options")
            val options = mutableListOf<String>()
            if (optionsArr != null) {
                for (i in 0 until optionsArr.length()) {
                    options.add(optionsArr.getJSONObject(i).optString("text"))
                }
            }

            if ((type == "closed" || type == "multiple") && options.size < 2) {
                return
            }

            val sig = "$type::$question::" + options.joinToString("|")
            val now = System.currentTimeMillis()
            if (sig == lastSolvedSignature && now - lastSolvedTime < 15000) {
                TelemetryLogger.log("bridge_duplicate_ignored", JSONObject().apply {
                    put("question", question.take(80))
                })
                return
            }

            currentSolveJob?.cancel()
            currentSolveJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val imageBase64 = if (imageUrl.isNotEmpty()) fetchImageBase64(imageUrl) else data.optString("imageBase64", null)

                    TelemetryLogger.log("bridge_question_received", JSONObject().apply {
                        put("type", type)
                        put("question", question)
                        put("isMultiple", isMultiple)
                        put("optionsCount", options.size)
                        put("hasImage", !imageBase64.isNullOrEmpty())
                    })

                    if (type == "closed" || type == "multiple") {
                        val correctIndices = AIService.solveClosedQuestion(
                            applicationContext,
                            question,
                            options,
                            isMultiple,
                            imageBase64
                        )

                        if (!isActive) return@launch

                        withContext(Dispatchers.Main) {
                            if (!isAIActive) return@withContext
                            val indicesStr = correctIndices.joinToString(",", "[", "]")
                            TelemetryLogger.log("bridge_decision_dispatched", JSONObject().apply {
                                put("indices", indicesStr)
                                put("question", question.take(80))
                            })
                            lastSolvedSignature = sig
                            lastSolvedTime = System.currentTimeMillis()
                            val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyClosed', correctIndices: $indicesStr } }));"
                            webView.evaluateJavascript(js, null)
                        }
                    } else if (type == "open") {
                        val answer = AIService.solveOpenQuestion(applicationContext, question, imageBase64)
                        if (!isActive) return@launch

                        withContext(Dispatchers.Main) {
                            if (!isAIActive) return@withContext
                            val escaped = answer.replace("\"", "\\\"").replace("\n", " ")
                            TelemetryLogger.log("bridge_open_dispatched", JSONObject().apply {
                                put("answer", escaped)
                            })
                            lastSolvedSignature = sig
                            lastSolvedTime = System.currentTimeMillis()
                            val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyOpen', answerText: \"$escaped\" } }));"
                            webView.evaluateJavascript(js, null)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        TelemetryLogger.log("bridge_error", JSONObject().apply {
                            put("error", e.message ?: e.toString())
                        })
                    }
                }
            }
        } catch (e: Exception) {
            TelemetryLogger.log("bridge_payload_parse_error", JSONObject().apply {
                put("error", e.message ?: e.toString())
            })
        }
    }

    private fun fetchImageBase64(urlStr: String): String? {
        return try {
            val url = java.net.URL(urlStr)
            val bytes = url.openStream().use { it.readBytes() }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAssetScript(filename: String): String {
        return try {
            assets.open(filename).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
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
