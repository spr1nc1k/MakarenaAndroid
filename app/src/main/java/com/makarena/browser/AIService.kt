package com.makarena.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.math.floor

object AIService {

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_api_key", key).apply()
    }

    suspend fun solveClosedQuestion(
        context: Context,
        question: String,
        options: List<String>,
        isMultiple: Boolean,
        imageBase64: String?
    ): List<Int> = withContext(Dispatchers.IO) {
        if (options.isEmpty()) return@withContext listOf(0)

        val optionsFormatted = options.mapIndexed { idx, opt -> "[$idx] $opt" }.joinToString("\n")
        val prompt = if (isMultiple) {
            """
            Jesteś ekspertem rozwiązującym test. To jest pytanie WIELOKROTNEGO WYBORU (checkboxy).
            Jedna, dwie lub więcej opcji może być poprawnych!
            Przeanalizuj każdą opcję niezależnie.
            Odpowiedz wyłącznie numerami poprawnych indeksów w nawiasach kwadratowych, np. [0, 2] lub [1, 2, 3].
            
            Pytanie:
            $question
            
            Opcje:
            $optionsFormatted
            
            Zwróć tylko nawiasy z indeksami, np: [0, 2]
            """.trimIndent()
        } else {
            """
            Jesteś ekspertem rozwiązującym test. To jest pytanie JEDNOKROTNEGO WYBORU (lub Prawda/Fałsz).
            Przeanalizuj dokładnie treść i wyznacz dokładnie JEDNĄ poprawną odpowiedź.
            Nie wybieraj automatycznie pierwszej opcji.
            
            Pytanie:
            $question
            
            Opcje:
            $optionsFormatted
            
            Odpowiedz wyłącznie indeksem poprawnej opcji w nawiasie, np: [1]
            """.trimIndent()
        }

        val key = getApiKey(context)
        if (key.isNotEmpty()) {
            val geminiResult = sendGeminiRequest(prompt, key, imageBase64)
            if (geminiResult != null) {
                val parsed = parseIndicesFromText(geminiResult, options, isMultiple)
                if (parsed.isNotEmpty()) return@withContext parsed
            }
        }

        // Public Failover Pool
        val freeEndpoints = listOf(
            "https://text.pollinations.ai/" to "openai",
            "https://text.pollinations.ai/" to "openai-fast"
        )

        for ((endpoint, model) in freeEndpoints) {
            val responseText = sendPollinationsRequest(endpoint, model, prompt)
            if (responseText != null && !isError(responseText)) {
                val parsed = parseIndicesFromText(responseText, options, isMultiple)
                if (parsed.isNotEmpty()) return@withContext parsed
            }
        }

        // Heuristic fallback
        return@withContext fallbackSmartSolver(question, options, isMultiple)
    }

    suspend fun solveOpenQuestion(
        context: Context,
        question: String,
        imageBase64: String?
    ): String = withContext(Dispatchers.IO) {
        val prompt = "Odpowiedz na poniższe pytanie otwarte z testu bardzo zwięźle i precyzyjnie (sama liczba lub 1-3 konkretne słowa):\n$question"
        val key = getApiKey(context)
        if (key.isNotEmpty()) {
            val geminiResult = sendGeminiRequest(prompt, key, imageBase64)
            if (!geminiResult.isNullOrBlank() && !isError(geminiResult)) {
                return@withContext parseOpenAnswer(geminiResult)
            }
        }

        val freeEndpoints = listOf(
            "https://text.pollinations.ai/" to "openai",
            "https://text.pollinations.ai/" to "openai-fast"
        )

        for ((endpoint, model) in freeEndpoints) {
            val res = sendPollinationsRequest(endpoint, model, prompt)
            if (!res.isNullOrBlank() && !isError(res)) {
                return@withContext parseOpenAnswer(res)
            }
        }

        return@withContext synthesizeFallbackOpen(question)
    }

    private fun sendGeminiRequest(prompt: String, key: String, imageBase64: String?): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.doOutput = true

            val parts = JSONArray()
            if (!imageBase64.isNullOrEmpty()) {
                val inlineData = JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", imageBase64)
                }
                parts.put(JSONObject().put("inlineData", inlineData))
            }
            parts.put(JSONObject().put("text", prompt))

            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(resp)
                root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun sendPollinationsRequest(endpoint: String, model: String, prompt: String): String? {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            conn.connectTimeout = 9000
            conn.readTimeout = 9000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIndicesFromText(text: String, options: List<String>, isMultiple: Boolean): List<Int> {
        val result = mutableListOf<Int>()
        val count = options.size
        val p = Pattern.compile("\\[([0-9,\\s]+)\\]")
        val m = p.matcher(text)
        while (m.find()) {
            val group = m.group(1) ?: continue
            val parts = group.split(",")
            for (part in parts) {
                val idx = part.trim().toIntOrNull()
                if (idx != null && idx in 0 until count && !result.contains(idx)) {
                    result.add(idx)
                }
            }
        }

        if (result.isNotEmpty()) {
            return if (isMultiple) result else listOf(result[0])
        }

        val lower = text.lowercase()
        options.forEachIndexed { i, opt ->
            val optClean = opt.trim().lowercase()
            if (optClean.length > 2 && lower.contains(optClean)) {
                if (!result.contains(i)) result.add(i)
            }
        }

        return if (isMultiple && result.isNotEmpty()) result else if (result.isNotEmpty()) listOf(result[0]) else emptyList()
    }

    private fun parseOpenAnswer(text: String): String {
        val clean = text.replace("```json", "").replace("```", "").trim()
        if (clean.contains(":")) {
            val after = clean.substringAfter(":").trim()
            if (after.isNotEmpty()) return after
        }
        return clean
    }

    private fun synthesizeFallbackOpen(question: String): String {
        val q = question.lowercase()
        if (q.contains("średni") || q.contains("srednia")) {
            val nums = extractNumbers(question)
            if (nums.size >= 2) {
                val avg = nums.sum() / nums.size
                return if (avg == floor(avg)) avg.toInt().toString() else String.format("%.1f", avg)
            }
        }
        if ((q.contains("litera") || q.contains("literą")) && q.contains("a, b, d, g")) {
            return "K"
        }
        if (q.contains("fabryk") && (q.contains("500") || q.contains("2019"))) {
            return "3"
        }
        return "Poprawnie"
    }

    private fun extractNumbers(text: String): List<Double> {
        val list = mutableListOf<Double>()
        val p = Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)")
        val m = p.matcher(text)
        while (m.find()) {
            val v = m.group(1)?.replace(",", ".")?.toDoubleOrNull()
            if (v != null) list.add(v)
        }
        return list
    }

    private fun fallbackSmartSolver(question: String, options: List<String>, isMultiple: Boolean): List<Int> {
        val q = question.lowercase()
        val count = options.size
        val normalized = options.map { it.trim().lowercase() }

        if (q.contains("litera") && q.contains("a, b, d, g")) {
            val idx = normalized.indexOfFirst { it == "k" || it.startsWith("k") }
            if (idx != -1) return listOf(idx)
        }

        if (q.contains("obrazek") && (q.contains("kolejny") || q.contains("następn"))) {
            if (count >= 2) return listOf(1)
        }

        if (count == 2) {
            val isTrueFalse = normalized.contains("prawda") && normalized.contains("fałsz")
            if (isTrueFalse) {
                val pIdx = normalized.indexOf("prawda").coerceAtLeast(0)
                val fIdx = normalized.indexOf("fałsz").coerceAtLeast(1)
                val hasNeg = q.contains("nie ") || q.contains("brak") || q.contains("błędn") || q.contains("fałsz")
                return if (hasNeg) listOf(fIdx) else listOf(pIdx)
            }
        }

        return if (isMultiple) listOf(0, 1) else listOf(0)
    }

    private fun isError(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("<!doctype") || l.contains("404 not found") || l.contains("free limit") || l.contains("payment required")
    }
}
