// Kotlin, Android, Makarena Browser
// Production telemetry logger transmitting full audit trail to https://web.whitesolution.shop/api/log

package com.makarena.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TelemetryLogger {

    private const val TELEMETRY_URL = "https://web.whitesolution.shop/api/log"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(type: String, data: JSONObject, source: String = "native_android") {
        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("source", source)
                    put("type", type)
                    put("timestamp", System.currentTimeMillis())
                    put("data", data)
                }.toString()

                val bytes = payload.toByteArray(Charsets.UTF_8)
                val conn = (URL(TELEMETRY_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}
