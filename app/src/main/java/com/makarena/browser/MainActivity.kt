// Kotlin, Android 10+, com.makarena.browser.MainActivity
package com.makarena.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                val conn = (URL(TELEMETRY_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
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

    private var isAIActive = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastToggleTime = 0L
    private var lastPanicTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupListeners()

        val startUrl = "https://www.testportal.pl"
        webView.loadUrl(startUrl)

        sendNativeLog("engine_initialized", JSONObject().apply {
            put("userAgent", webView.settings.userAgentString)
            put("isAIActive", isAIActive)
        })
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
        isAIActive = prefs.getBoolean("is_ai_active", true)
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
                val urlStr = request?.url?.toString() ?: return null

                if (urlStr.contains("testportal", ignoreCase = true) && !request.isForMainFrame) {
                    val isPotentiallyBlocked = urlStr.endsWith(".ico") ||
                                               urlStr.contains("favicon") ||
                                               urlStr.contains("tracking") ||
                                               urlStr.contains("analytics") ||
                                               urlStr.contains("beacon")

                    if (isPotentiallyBlocked) {
                        try {
                            val conn = URL(urlStr).openConnection() as HttpURLConnection
                            conn.requestMethod = request.method ?: "GET"
                            conn.connectTimeout = 4000
                            conn.readTimeout = 4000
                            conn.instanceFollowRedirects = true

                            request.requestHeaders?.forEach { (key, value) ->
                                if (!key.equals("X-Requested-With", ignoreCase = true)) {
                                    conn.setRequestProperty(key, value)
                                }
                            }

                            val cookie = CookieManager.getInstance().getCookie(urlStr)
                            if (!cookie.isNullOrEmpty()) {
                                conn.setRequestProperty("Cookie", cookie)
                            }

                            if (conn.getRequestProperty("User-Agent") == null) {
                                conn.setRequestProperty("User-Agent", chromeUserAgent)
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
                        } catch (_: Exception) {
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
                sendNativeLog("native_onPageStarted", JSONObject().apply {
                    put("url", url)
                    put("isAIActive", isAIActive)
                })

                if (!hasDocStart && bypassScript.isNotEmpty()) {
                    view?.evaluateJavascript(bypassScript, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webView.requestFocus()
                sendNativeLog("native_onPageFinished", JSONObject().apply {
                    put("url", url)
                    put("isAIActive", isAIActive)
                })

                val injectorScript = loadAssetScript("ai_injector.js")
                if (injectorScript.isNotEmpty()) {
                    view?.evaluateJavascript("window.__makarena_ai_active = $isAIActive;", null)
                    view?.evaluateJavascript(injectorScript, null)
                }

                if (isAIActive) {
                    mainHandler.postDelayed({
                        if (isAIActive) {
                            sendNativeLog("native_trigger_ai_delayed", JSONObject().apply { put("url", url) })
                            triggerAIAnalysis()
                        }
                    }, 400)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
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
                        } catch (_: Exception) {
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

        gestureStrip.setOnClickListener(object : View.OnClickListener {
            private var lastTap = 0L
            private var tapCount = 0

            override fun onClick(v: View?) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 400) {
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

    private fun triggerHaptic(type: String) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    when (type) {
                        "on" -> vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
                        "off" -> {
                            val pattern = longArrayOf(0, 50, 50, 50)
                            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                        }
                        "panic" -> vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(140)
                }
            }
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        sendNativeLog("native_key_event", JSONObject().apply {
            put("keyCode", event.keyCode)
            put("action", event.action)
            put("repeatCount", event.repeatCount)
        })

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = System.currentTimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (now - lastToggleTime > 350) {
                        lastToggleTime = now
                        toggleAIAssistant()
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (now - lastPanicTime > 350) {
                        lastPanicTime = now
                        triggerPanicMode()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleAIAssistant() {
        isAIActive = !isAIActive
        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_ai_active", isAIActive).apply()

        triggerHaptic(if (isAIActive) "on" else "off")
        Toast.makeText(
            this,
            if (isAIActive) "I GOT U DAWG (AI ON)" else "AI OFF",
            Toast.LENGTH_SHORT
        ).show()

        updateStatusPill(true)

        sendNativeLog("native_ai_toggle", JSONObject().apply {
            put("isAIActive", isAIActive)
        })

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

        triggerHaptic("panic")
        Toast.makeText(this, "PANIC MODE (ALL OFF)", Toast.LENGTH_SHORT).show()

        updateStatusPill(true)

        sendNativeLog("native_panic_mode", JSONObject().apply {
            put("isAIActive", false)
        })

        val js = "window.__makarena_ai_active = false; document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'panic' } }));"
        webView.evaluateJavascript(js, null)
    }

    private fun updateStatusPill(animate: Boolean) {
        if (isAIActive) {
            statusDot.setBackgroundColor(android.graphics.Color.WHITE)
            statusText.text = "I GOT U DAWG"
            statusPill.visibility = View.VISIBLE
            statusPill.alpha = 1f

            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                if (isAIActive) {
                    statusPill.animate().alpha(0f).setDuration(400).withEndAction {
                        statusPill.visibility = View.INVISIBLE
                    }.start()
                }
            }, 3000)
        } else {
            statusPill.animate().alpha(0f).setDuration(250).withEndAction {
                statusPill.visibility = View.INVISIBLE
            }.start()
        }
    }

    private fun handleBridgePayload(payload: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(payload)
                val action = json.optString("action")
                sendNativeLog("bridge_payload_received", JSONObject().apply {
                    put("action", action)
                    put("isAIActive", isAIActive)
                })

                if (action != "questionDataExtracted") return@launch
                if (!isAIActive) return@launch

                val data = json.getJSONObject("data")
                val type = data.optString("type")
                val question = data.optString("question")
                val isMultiple = (type == "multiple")
                val imageUrl = data.optString("imageUrl", "")
                val imageBase64 = if (imageUrl.isNotEmpty()) fetchImageBase64(imageUrl) else data.optString("imageBase64", null)

                val optionsArr = data.optJSONArray("options") ?: JSONArray()
                val options = mutableListOf<String>()
                for (i in 0 until optionsArr.length()) {
                    options.add(optionsArr.getJSONObject(i).optString("text"))
                }

                sendNativeLog("bridge_question_extracted", JSONObject().apply {
                    put("type", type)
                    put("question", question)
                    put("options", JSONArray(options))
                    put("optionsCount", options.size)
                    put("isMultiple", isMultiple)
                    put("hasImage", imageBase64 != null)
                })

                if (type == "closed" || type == "multiple") {
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
                        put("chosenOptions", JSONArray(correctIndices.mapNotNull { options.getOrNull(it) }))
                    })

                    withContext(Dispatchers.Main) {
                        if (!isAIActive) {
                            sendNativeLog("bridge_dispatch_aborted_inactive", JSONObject())
                            return@withContext
                        }
                        if (correctIndices.isNotEmpty()) {
                            val indicesStr = correctIndices.joinToString(",", "[", "]")
                            sendNativeLog("bridge_dispatching_applyClosed", JSONObject().apply {
                                put("indicesStr", indicesStr)
                            })
                            val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyClosed', correctIndices: $indicesStr } }));"
                            webView.evaluateJavascript(js, null)
                        } else {
                            sendNativeLog("bridge_no_indices_to_highlight", JSONObject().apply {
                                put("reason", "empty_indices_returned")
                            })
                        }
                    }
                } else if (type == "open") {
                    val answer = AIService.solveOpenQuestion(applicationContext, question, imageBase64)

                    sendNativeLog("bridge_open_resolved", JSONObject().apply {
                        put("answer", answer)
                    })

                    withContext(Dispatchers.Main) {
                        if (!isAIActive) return@withContext
                        if (answer.isNotBlank()) {
                            val escaped = answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
                            val js = "document.dispatchEvent(new CustomEvent('__makarena_action', { detail: { action: 'applyOpen', answerText: \"$escaped\" } }));"
                            webView.evaluateJavascript(js, null)
                        }
                    }
                }
            } catch (e: Exception) {
                sendNativeLog("bridge_handle_exception", JSONObject().apply {
                    put("error", e.message ?: e.toString())
                })
            }
        }
    }

    private fun fetchImageBase64(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
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
