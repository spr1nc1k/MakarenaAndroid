// Kotlin, Android, Makarena Browser
// Production AI Solver Engine with multi-provider rotation across 100% free providers

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

object AIService {

    fun getGeminiKeys(context: Context): List<String> {
        val prefs = context.getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("gemini_api_key", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(Regex("[,;\\s\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val DEFAULT_GROQ_KEYS = listOf(
        byteArrayOf(61, 41, 49, 5, 0, 20, 55, 16, 34, 35, 48, 11, 3, 35, 51, 40, 14, 63, 3, 99, 8, 30, 52, 104, 13, 29, 62, 35, 56, 105, 28, 3, 48, 107, 19, 11, 14, 17, 10, 60, 111, 105, 18, 55, 106, 110, 35, 59, 46, 99, 51, 25, 43, 32, 43, 18)
            .map { (it.toInt() xor 0x5a).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    )

    fun getGroqKeys(context: Context): List<String> {
        val prefs = context.getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("groq_api_key", "") ?: ""
        if (raw.isEmpty()) return DEFAULT_GROQ_KEYS
        val keys = raw.split(Regex("[,;\\s\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (keys.isNotEmpty()) keys else DEFAULT_GROQ_KEYS
    }

    fun getOpenRouterKeys(context: Context): List<String> {
        val prefs = context.getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("openrouter_api_key", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(Regex("[,;\\s\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun cleanOptionText(text: String): String {
        return text.replace(Regex("^[A-Ja-j][\\.\\)\\:\\-]\\s*"), "")
            .replace(Regex("^[0-9]+[\\.\\)\\:\\-]\\s*"), "")
            .trim()
    }

    suspend fun solveClosedQuestion(
        context: Context,
        question: String,
        options: List<String>,
        isMultiple: Boolean,
        imageBase64: String?
    ): List<Int> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (options.isEmpty()) return@withContext listOf(0)

        val geminiKeys = getGeminiKeys(context)
        val groqKeys = getGroqKeys(context)
        val openRouterKeys = getOpenRouterKeys(context)

        val cleanedOptions = options.map { cleanOptionText(it) }
        val optionsFormatted = cleanedOptions.mapIndexed { idx, opt -> "[$idx] $opt" }.joinToString("\n")

        val prompt = if (isMultiple) {
            """
            Jesteś wybitnym ekspertem rozwiązującym test wielokrotnego wyboru.
            W tym pytaniu JEDNA, DWIE LUB WIĘCEJ opcji może być poprawnych (checkboxy).
            Przeanalizuj każdą opcję po kolei pod kątem faktów.
            
            Pytanie:
            $question
            
            Opcje do wyboru:
            $optionsFormatted
            
            Format odpowiedzi (dokładnie 2 linie):
            Krótkie uzasadnienie faktów (1 zdanie): ...
            Poprawne indeksy w nawiasie kwadratowym: [INDEKSY] (np. [0, 2])
            """.trimIndent()
        } else {
            """
            Jesteś wybitnym ekspertem rozwiązującym test jednokrotnego wyboru.
            Dokładnie JEDNA opcja jest w 100% poprawna.
            WAŻNE: Opcja [0] NIE jest odpowiedzią domyślną. Zidentyfikuj właściwy fakt.
            
            Pytanie:
            $question
            
            Opcje do wyboru:
            $optionsFormatted
            
            Format odpowiedzi (dokładnie 2 linie):
            Krótkie uzasadnienie faktów (1 zdanie): ...
            Poprawny indeks w nawiasie kwadratowym: [INDEKS] (np. [1])
            """.trimIndent()
        }

        TelemetryLogger.log("solver_started", JSONObject().apply {
            put("question", question)
            put("options", JSONArray(options))
            put("isMultiple", isMultiple)
            put("geminiKeysCount", geminiKeys.size)
            put("groqKeysCount", groqKeys.size)
            put("openRouterKeysCount", openRouterKeys.size)
            put("hasImage", !imageBase64.isNullOrEmpty())
        })

        // 1. Google Gemini Provider Pool
        if (geminiKeys.isNotEmpty()) {
            val geminiModels = listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-flash-8b")
            for ((keyIndex, key) in geminiKeys.withIndex()) {
                for (model in geminiModels) {
                    val attemptStart = System.currentTimeMillis()
                    TelemetryLogger.log("solver_api_attempt", JSONObject().apply {
                        put("provider", "gemini")
                        put("model", model)
                        put("keyIndex", keyIndex)
                    })

                    val resp = sendGeminiRequest(prompt, key, model, imageBase64)
                    val duration = System.currentTimeMillis() - attemptStart

                    TelemetryLogger.log("solver_api_response", JSONObject().apply {
                        put("provider", "gemini")
                        put("model", model)
                        put("statusCode", resp.statusCode)
                        put("rawResponse", resp.body ?: resp.errorMessage ?: "")
                        put("durationMs", duration)
                    })

                    if (resp.statusCode == 200 && !resp.body.isNullOrEmpty()) {
                        val parsed = parseIndicesFromText(resp.body, options, isMultiple)
                        if (parsed.isNotEmpty()) {
                            TelemetryLogger.log("solver_final_decision", JSONObject().apply {
                                put("provider", "gemini")
                                put("model", model)
                                put("selectedIndices", JSONArray(parsed))
                                put("totalDurationMs", System.currentTimeMillis() - startTime)
                            })
                            return@withContext parsed
                        }
                    }
                }
            }
        }

        // 2. Groq Provider Pool (Ultra-fast 200ms reasoning / LLaMA / Qwen, 30 RPM permanent free)
        if (groqKeys.isNotEmpty()) {
            val groqModels = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")
            for ((keyIndex, key) in groqKeys.withIndex()) {
                for (model in groqModels) {
                    val attemptStart = System.currentTimeMillis()
                    TelemetryLogger.log("solver_api_attempt", JSONObject().apply {
                        put("provider", "groq")
                        put("model", model)
                        put("keyIndex", keyIndex)
                    })

                    val resp = sendOpenAiCompatibleRequest("https://api.groq.com/openai/v1/chat/completions", key, model, prompt)
                    val duration = System.currentTimeMillis() - attemptStart

                    TelemetryLogger.log("solver_api_response", JSONObject().apply {
                        put("provider", "groq")
                        put("model", model)
                        put("statusCode", resp.statusCode)
                        put("rawResponse", resp.body ?: resp.errorMessage ?: "")
                        put("durationMs", duration)
                    })

                    if (resp.statusCode == 200 && !resp.body.isNullOrEmpty()) {
                        val parsed = parseIndicesFromText(resp.body, options, isMultiple)
                        if (parsed.isNotEmpty()) {
                            TelemetryLogger.log("solver_final_decision", JSONObject().apply {
                                put("provider", "groq")
                                put("model", model)
                                put("selectedIndices", JSONArray(parsed))
                                put("totalDurationMs", System.currentTimeMillis() - startTime)
                            })
                            return@withContext parsed
                        }
                    }
                }
            }
        }

        // 3. OpenRouter Free Models Pool
        if (openRouterKeys.isNotEmpty()) {
            val freeModels = listOf(
                "google/gemini-2.0-flash-exp:free",
                "meta-llama/llama-3.3-70b-instruct:free",
                "deepseek/deepseek-r1:free"
            )
            for ((keyIndex, key) in openRouterKeys.withIndex()) {
                for (model in freeModels) {
                    val attemptStart = System.currentTimeMillis()
                    TelemetryLogger.log("solver_api_attempt", JSONObject().apply {
                        put("provider", "openrouter")
                        put("model", model)
                        put("keyIndex", keyIndex)
                    })

                    val resp = sendOpenAiCompatibleRequest("https://openrouter.ai/api/v1/chat/completions", key, model, prompt)
                    val duration = System.currentTimeMillis() - attemptStart

                    TelemetryLogger.log("solver_api_response", JSONObject().apply {
                        put("provider", "openrouter")
                        put("model", model)
                        put("statusCode", resp.statusCode)
                        put("rawResponse", resp.body ?: resp.errorMessage ?: "")
                        put("durationMs", duration)
                    })

                    if (resp.statusCode == 200 && !resp.body.isNullOrEmpty()) {
                        val parsed = parseIndicesFromText(resp.body, options, isMultiple)
                        if (parsed.isNotEmpty()) {
                            TelemetryLogger.log("solver_final_decision", JSONObject().apply {
                                put("provider", "openrouter")
                                put("model", model)
                                put("selectedIndices", JSONArray(parsed))
                                put("totalDurationMs", System.currentTimeMillis() - startTime)
                            })
                            return@withContext parsed
                        }
                    }
                }
            }
        }

        // 4. WhiteSolution Server Solver Relay
        try {
            val serverStart = System.currentTimeMillis()
            TelemetryLogger.log("solver_api_attempt", JSONObject().apply {
                put("provider", "whitesolution_server")
                put("endpoint", "https://web.whitesolution.shop/api/solve")
            })

            val serverResp = sendServerSolveRequest(question, options, isMultiple)
            val duration = System.currentTimeMillis() - serverStart

            TelemetryLogger.log("solver_api_response", JSONObject().apply {
                put("provider", "whitesolution_server")
                put("statusCode", serverResp.statusCode)
                put("rawResponse", serverResp.body ?: serverResp.errorMessage ?: "")
                put("durationMs", duration)
            })

            if (serverResp.statusCode == 200 && serverResp.body != null) {
                val sObj = JSONObject(serverResp.body)
                val arr = sObj.optJSONArray("indices")
                if (arr != null && arr.length() > 0) {
                    val indices = mutableListOf<Int>()
                    for (i in 0 until arr.length()) indices.add(arr.getInt(i))
                    TelemetryLogger.log("solver_final_decision", JSONObject().apply {
                        put("provider", "whitesolution_server")
                        put("selectedIndices", JSONArray(indices))
                        put("totalDurationMs", System.currentTimeMillis() - startTime)
                    })
                    return@withContext indices
                }
            }
        } catch (_: Exception) {}

        // 5. Direct Pollinations Fallback
        val freeEndpoints = listOf(
            "https://text.pollinations.ai/" to "openai",
            "https://text.pollinations.ai/" to "openai-fast"
        )
        for ((endpoint, model) in freeEndpoints) {
            val resp = sendPollinationsRequest(endpoint, model, prompt)
            if (resp.statusCode == 200 && !resp.body.isNullOrEmpty() && !isError(resp.body)) {
                val parsed = parseIndicesFromText(resp.body, options, isMultiple)
                if (parsed.isNotEmpty()) {
                    TelemetryLogger.log("solver_final_decision", JSONObject().apply {
                        put("provider", "pollinations_direct")
                        put("selectedIndices", JSONArray(parsed))
                        put("totalDurationMs", System.currentTimeMillis() - startTime)
                    })
                    return@withContext parsed
                }
            }
        }

        // 6. Heuristic Fallback
        val fallbackIndices = fallbackSmartSolver(question, options, isMultiple)
        TelemetryLogger.log("solver_fallback_invoked", JSONObject().apply {
            put("reason", "Wszystkie zewnetrzne AI wyczerpane")
            put("selectedIndices", JSONArray(fallbackIndices))
            put("totalDurationMs", System.currentTimeMillis() - startTime)
        })

        return@withContext fallbackIndices
    }

    suspend fun solveOpenQuestion(
        context: Context,
        question: String,
        imageBase64: String?
    ): String = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val geminiKeys = getGeminiKeys(context)
        val groqKeys = getGroqKeys(context)
        val prompt = """
        Jesteś wybitnym ekspertem rozwiązującym test wiedzy.
        Odpowiedz na poniższe pytanie otwarte bezpośrednio w języku polskim.
        Jeśli pytanie wymaga podania daty, nazwiska, pojęcia lub liczby - podaj konkretną informację.
        Jeśli wymaga wyjaśnienia, definicji lub podania różnicy - podaj 1-3 zwięzłe, merytoryczne i wyczerpujące zdania.
        ZAKAZ pisania wstępów typu "Oto odpowiedź", "Odpowiedź na pytanie" ani żadnych tekstów po angielsku. Zwróć wyłącznie samą treść odpowiedzi.
        
        Pytanie otwarte:
        $question
        """.trimIndent()

        // 1. Groq Qwen 3.8 27B / GPT-OSS Pool (0.4s direct Polish answers)
        if (groqKeys.isNotEmpty()) {
            val openModels = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "openai/gpt-oss-20b")
            for (key in groqKeys) {
                for (model in openModels) {
                    val resp = sendOpenAiCompatibleRequest("https://api.groq.com/openai/v1/chat/completions", key, model, prompt)
                    if (resp.statusCode == 200 && !resp.body.isNullOrBlank() && !isError(resp.body)) {
                        val parsed = parseOpenAnswer(resp.body)
                        if (parsed.isNotEmpty()) return@withContext parsed
                    }
                }
            }
        }

        // 2. Gemini Fallback
        if (geminiKeys.isNotEmpty()) {
            for (key in geminiKeys) {
                val resp = sendGeminiRequest(prompt, key, "gemini-2.0-flash", imageBase64)
                if (resp.statusCode == 200 && !resp.body.isNullOrBlank() && !isError(resp.body)) {
                    val parsed = parseOpenAnswer(resp.body)
                    if (parsed.isNotEmpty()) return@withContext parsed
                }
            }
        }

        // 3. Remote Server Solver Relay
        try {
            val serverResp = sendServerSolveOpenRequest(question)
            if (serverResp.statusCode == 200 && serverResp.body != null) {
                val sObj = JSONObject(serverResp.body)
                val ans = sObj.optString("answer", "")
                if (ans.isNotEmpty()) return@withContext parseOpenAnswer(ans)
            }
        } catch (_: Exception) {}

        // 4. Pollinations Fallback
        val freeResp = sendPollinationsRequest("https://text.pollinations.ai/", "openai", prompt)
        if (freeResp.statusCode == 200 && !freeResp.body.isNullOrBlank() && !isError(freeResp.body)) {
            val parsed = parseOpenAnswer(freeResp.body)
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        val fallback = synthesizeFallbackOpen(question)
        return@withContext fallback
    }

    data class ApiResponse(val statusCode: Int, val body: String?, val errorMessage: String?)

    private fun sendOpenAiCompatibleRequest(endpoint: String, key: String, model: String, prompt: String): ApiResponse {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.1)
                put("max_tokens", 450)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(resp)
                val msgObj = root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                var text = msgObj.optString("content", "").trim()
                val reasoning = msgObj.optString("reasoning", "").trim()
                if (text.isEmpty() && reasoning.isNotEmpty()) {
                    // Extract answer portion from reasoning if content is empty
                    text = reasoning
                }
                ApiResponse(code, text, null)
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                ApiResponse(code, null, err)
            }
        } catch (e: Exception) {
            ApiResponse(0, null, e.message ?: e.toString())
        }
    }

    private fun sendGeminiRequest(prompt: String, key: String, model: String, imageBase64: String?): ApiResponse {
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
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

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(resp)
                val text = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                ApiResponse(code, text, null)
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                ApiResponse(code, null, err)
            }
        } catch (e: Exception) {
            ApiResponse(0, null, e.message ?: e.toString())
        }
    }

    private fun sendServerSolveRequest(question: String, options: List<String>, isMultiple: Boolean): ApiResponse {
        return try {
            val url = URL("https://web.whitesolution.shop/api/solve")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("question", question)
                put("options", JSONArray(options))
                put("isMultiple", isMultiple)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                ApiResponse(code, resp, null)
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                ApiResponse(code, null, err)
            }
        } catch (e: Exception) {
            ApiResponse(0, null, e.message ?: e.toString())
        }
    }

    private fun sendServerSolveOpenRequest(question: String): ApiResponse {
        return try {
            val url = URL("https://web.whitesolution.shop/api/solve_open")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("question", question)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                ApiResponse(code, resp, null)
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                ApiResponse(code, null, err)
            }
        } catch (e: Exception) {
            ApiResponse(0, null, e.message ?: e.toString())
        }
    }

    private fun sendPollinationsRequest(endpoint: String, model: String, prompt: String): ApiResponse {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                ApiResponse(code, resp, null)
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                ApiResponse(code, null, err)
            }
        } catch (e: Exception) {
            ApiResponse(0, null, e.message ?: e.toString())
        }
    }

    fun parseIndicesFromText(text: String, options: List<String>, isMultiple: Boolean): List<Int> {
        val result = mutableListOf<Int>()
        val count = options.size
        if (count == 0) return emptyList()

        // 1. Bracketed numbers: [0], [1, 2]
        val pBrackets = Pattern.compile("\\[([0-9,\\s]+)\\]")
        val mBrackets = pBrackets.matcher(text)
        while (mBrackets.find()) {
            val group = mBrackets.group(1) ?: continue
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

        // 2. Parentheses numbers: (0), (1)
        val pParens = Pattern.compile("\\(([0-9,\\s]+)\\)")
        val mParens = pParens.matcher(text)
        while (mParens.find()) {
            val group = mParens.group(1) ?: continue
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

        // 3. Letters: [A], (B), A., B.
        val pLetterBrackets = Pattern.compile("[\\[\\(]?([A-Ja-j])[\\]\\)\\.]")
        val mLetterBrackets = pLetterBrackets.matcher(text)
        while (mLetterBrackets.find()) {
            val letter = mLetterBrackets.group(1)?.uppercase() ?: continue
            if (letter.isNotEmpty()) {
                val idx = letter[0] - 'A'
                if (idx in 0 until count && !result.contains(idx)) {
                    result.add(idx)
                }
            }
        }
        if (result.isNotEmpty()) {
            return if (isMultiple) result else listOf(result[0])
        }

        // 4. Standalone single digit or letter
        val trimmed = text.trim()
        val singleDigit = trimmed.toIntOrNull()
        if (singleDigit != null && singleDigit in 0 until count) {
            return listOf(singleDigit)
        }
        if (trimmed.length == 1 && trimmed[0].uppercaseChar() in 'A'..'J') {
            val idx = trimmed[0].uppercaseChar() - 'A'
            if (idx in 0 until count) return listOf(idx)
        }

        // 5. Substring match
        val lower = text.lowercase()
        options.forEachIndexed { i, opt ->
            val optClean = opt.trim().lowercase()
            if (optClean.length >= 3 && lower.contains(optClean)) {
                if (!result.contains(i)) result.add(i)
            }
        }

        return if (isMultiple && result.isNotEmpty()) result else if (result.isNotEmpty()) listOf(result[0]) else emptyList()
    }

    private fun parseOpenAnswer(text: String): String {
        var clean = text.replace(Regex("```[a-zA-Z]*"), "").replace("```", "").trim()
        clean = clean.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        
        // Strip leaked prompt / English meta chatter
        if (clean.contains("user asks:", ignoreCase = true) || clean.contains("The user asks:", ignoreCase = true)) {
            val lines = clean.split("\n").filter { line ->
                val l = line.lowercase()
                !l.contains("user asks") && !l.contains("they want") && !l.contains("odpowiedz na")
            }
            clean = lines.joinToString(" ").trim()
        }

        if (clean.startsWith("Odpowiedź:", ignoreCase = true)) {
            clean = clean.replace(Regex("^Odpowiedź:\\s*", RegexOption.IGNORE_CASE), "").trim()
        } else if (clean.startsWith("Odp:", ignoreCase = true)) {
            clean = clean.replace(Regex("^Odp:\\s*", RegexOption.IGNORE_CASE), "").trim()
        } else if (clean.startsWith("Uzasadnienie:", ignoreCase = true)) {
            clean = clean.replace(Regex("^Uzasadnienie:\\s*", RegexOption.IGNORE_CASE), "").trim()
        }

        return clean
    }

    private fun synthesizeFallbackOpen(question: String): String {
        val q = question.lowercase()
        if (q.contains("średni") || q.contains("srednia")) {
            val nums = extractNumbers(question)
            if (nums.size >= 2) {
                val avg = nums.sum() / nums.size
                return if (avg == kotlin.math.floor(avg)) avg.toInt().toString() else String.format("%.1f", avg)
            }
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
        if (count == 0) return listOf(0)
        val normalized = options.map { it.trim().lowercase() }

        if (count == 2) {
            val pIdx = normalized.indexOfFirst { it == "prawda" || it == "true" }
            val fIdx = normalized.indexOfFirst { it == "fałsz" || it == "false" }
            if (pIdx != -1 && fIdx != -1) {
                val hasNeg = q.contains("nie ") || q.contains("brak") || q.contains("błędn") || q.contains("fałsz") || q.contains("nieprawd")
                return if (hasNeg) listOf(fIdx) else listOf(pIdx)
            }
        }

        normalized.forEachIndexed { i, opt ->
            if (opt.contains("wszystkie powyższ") || opt.contains("wszystkie z powyższ") || opt.contains("all of the above")) {
                return listOf(i)
            }
        }

        val qWords = Regex("[\\p{L}0-9]{3,}").findAll(q).map { it.value }.toSet()
        val scores = IntArray(count)

        options.forEachIndexed { idx, opt ->
            val optClean = cleanOptionText(opt).lowercase()
            val optWords = Regex("[\\p{L}0-9]{3,}").findAll(optClean).map { it.value }.toSet()
            var s = 0
            for (w in optWords) {
                if (qWords.contains(w)) s += 5
            }
            val datesInQ = Regex("\\b[0-9]{2,4}\\b").findAll(q).map { it.value }.toSet()
            for (d in datesInQ) {
                if (optClean.contains(d)) s += 10
            }
            scores[idx] = s
        }

        val maxScore = scores.maxOrNull() ?: 0
        if (maxScore > 0) {
            val bestIdx = scores.indexOf(maxScore)
            return if (isMultiple) {
                val above = scores.indices.filter { scores[it] >= maxScore / 2 && scores[it] > 0 }
                if (above.isNotEmpty()) above else listOf(bestIdx)
            } else {
                listOf(bestIdx)
            }
        }

        var bestIdx = 0
        var bestScore = -1
        options.forEachIndexed { idx, opt ->
            val optClean = cleanOptionText(opt)
            val score = optClean.length
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }

        return if (isMultiple) listOf(bestIdx) else listOf(bestIdx)
    }

    private fun isError(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("<!doctype") || l.contains("404 not found") || l.contains("free limit") || l.contains("payment required") || l.contains("rate limit")
    }
}
