// Kotlin, Android 10+, com.makarena.browser.AIService
package com.makarena.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern
import kotlin.math.floor

object AIService {

    private const val TELEMETRY_URL = "https://web.whitesolution.shop/api/log"
    // Wikimedia-compliant User-Agent policy: <ClientName>/<Version> (<URL>; <ContactEmail>)
    private const val WIKI_USER_AGENT = "WhiteWebBrowser/1.2.3 (https://whitesolution.shop; support@whitesolution.shop) Android/10"

    fun logTelemetry(type: String, data: JSONObject) {
        try {
            val payload = JSONObject().apply {
                put("source", "native_ai_service")
                put("type", type)
                put("timestamp", System.currentTimeMillis())
                put("data", data)
            }.toString()

            val bytes = payload.toByteArray(Charsets.UTF_8)
            val conn = (URL(TELEMETRY_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
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
        if (options.isEmpty()) return@withContext emptyList()
        if (options.size == 1) return@withContext listOf(0)

        val key = getApiKey(context)
        logTelemetry("solver_closed_invoked", JSONObject().apply {
            put("question", question)
            put("options", JSONArray(options))
            put("isMultiple", isMultiple)
            put("hasGeminiKey", key.isNotEmpty())
            put("hasImage", imageBase64 != null)
        })

        // 1. Gemini Vision API (jeśli klucz jest skonfigurowany w Ustawieniach)
        if (key.isNotEmpty()) {
            val optionsFormatted = options.mapIndexed { idx, opt -> "[$idx] $opt" }.joinToString("\n")
            val prompt = if (isMultiple) {
                """
                Jesteś ekspertem rozwiązującym test. Pytanie WIELOKROTNEGO WYBORU.
                Odpowiedz wyłącznie indeksami poprawnych opcji w nawiasach kwadratowych, np. [0, 2] lub [1, 3].
                
                Pytanie:
                $question
                
                Opcje:
                $optionsFormatted
                """.trimIndent()
            } else {
                """
                Jesteś ekspertem rozwiązującym test. Pytanie JEDNOKROTNEGO WYBORU.
                Dokładnie JEDNA opcja jest poprawna.
                
                Pytanie:
                $question
                
                Opcje:
                $optionsFormatted
                
                Odpowiedz wyłącznie indeksem poprawnej opcji w nawiasie, np. [1] lub [2]:
                """.trimIndent()
            }

            val geminiResult = sendGeminiRequest(prompt, key, imageBase64)
            logTelemetry("solver_gemini_response", JSONObject().apply {
                put("rawResponse", geminiResult ?: "null")
            })

            if (geminiResult != null) {
                val parsed = parseIndicesFromText(geminiResult, options, isMultiple)
                if (parsed.isNotEmpty()) {
                    logTelemetry("solver_gemini_parsed", JSONObject().apply {
                        put("indices", JSONArray(parsed))
                        put("chosenOptions", JSONArray(parsed.mapNotNull { options.getOrNull(it) }))
                    })
                    return@withContext parsed
                }
            }
        }

        // 2. Bezwzględny semantyczny silnik faktów
        val solvedIndices = solveViaSemanticEngine(question, options, isMultiple)
        logTelemetry("solver_closed_result", JSONObject().apply {
            put("indices", JSONArray(solvedIndices))
            put("chosenOptions", JSONArray(solvedIndices.mapNotNull { options.getOrNull(it) }))
            put("optionsCount", options.size)
        })

        return@withContext solvedIndices
    }

    suspend fun solveOpenQuestion(
        context: Context,
        question: String,
        imageBase64: String?
    ): String = withContext(Dispatchers.IO) {
        val key = getApiKey(context)
        logTelemetry("solver_open_invoked", JSONObject().apply {
            put("question", question)
            put("hasGeminiKey", key.isNotEmpty())
        })

        if (key.isNotEmpty()) {
            val prompt = "Odpowiedz na poniższe pytanie otwarte z testu bardzo zwięźle, konkretnie i precyzyjnie (sama liczba, data, wynik lub 1-3 słowa kluczowe):\n$question"
            val geminiResult = sendGeminiRequest(prompt, key, imageBase64)
            if (!geminiResult.isNullOrBlank() && !isError(geminiResult)) {
                val parsed = parseOpenAnswer(geminiResult)
                if (parsed.isNotBlank() && !isDummyPlaceholder(parsed)) {
                    logTelemetry("solver_open_gemini_answer", JSONObject().apply { put("answer", parsed) })
                    return@withContext parsed
                }
            }
        }

        val dynamicAnswer = extractDynamicEntity(question)
        if (dynamicAnswer.isNotBlank()) {
            logTelemetry("solver_open_dynamic_answer", JSONObject().apply { put("answer", dynamicAnswer) })
            return@withContext dynamicAnswer
        }

        val mathAnswer = synthesizeDynamicOpen(question)
        logTelemetry("solver_open_math_answer", JSONObject().apply { put("answer", mathAnswer) })
        return@withContext mathAnswer
    }

    private fun sendGeminiRequest(prompt: String, key: String, imageBase64: String?): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 7000
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
                put("generationConfig", JSONObject().put("temperature", 0.1))
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

    private fun cleanQuestionText(raw: String): String {
        return raw
            .replace(Regex("^(?:pytanie|pyt|zadanie|zad)?\\s*\\.?:?\\s*[0-9]+(?:\\s*\\.?:?\\s*|\\s*[-–—]\\s*)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Pozostały\\s+czas[:\\s]+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Czas\\s+na\\s+odpowiedź[:\\s]+[0-9]{1,2}:[0-9]{2}", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:Wskaż|Wybierz|Zaznacz|Oceń)\\s+(?:poprawną|prawidłową|właściwą)?\\s*(?:odpowiedź|zdanie)?[:\\s]*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun getStem(w: String): String {
        var s = w.lowercase()
        val suffixes = listOf("owych", "owej", "owie", "ami", "ach", "ego", "emu", "iej", "ych", "imi", "em", "om", "ie", "iu", "ia", "ów", "e", "y", "a", "u", "i")
        for (suff in suffixes) {
            if (s.length > suff.length + 3 && s.endsWith(suff)) {
                s = s.substring(0, s.length - suff.length)
                break
            }
        }
        return s
    }

    private fun solveViaSemanticEngine(question: String, options: List<String>, isMultiple: Boolean): List<Int> {
        return try {
            val cleanQ = cleanQuestionText(question)
            val normalizedOptions = options.map { it.trim().lowercase() }

            val stopwords = setOf(
                "kto", "co", "to", "jak", "jaki", "jaka", "jakie", "który", "która", "które", "których", "którym",
                "był", "była", "było", "były", "miał", "miała", "mieli", "oraz", "jest", "są", "dla", "nad", "pod",
                "się", "ze", "za", "do", "na", "od", "w", "z", "o", "i", "a", "jedną", "jedna", "jedno", "jednym",
                "wskaż", "wybierz", "zaznacz", "oceń", "pytanie", "zadanie", "prawda", "fałsz", "odpowiedź", "poprawna"
            )

            // A. Prawda / Fałsz
            val isTrueFalse = (normalizedOptions.contains("prawda") && normalizedOptions.contains("fałsz")) ||
                              (normalizedOptions.contains("tak") && normalizedOptions.contains("nie"))
            if (options.size == 2 && isTrueFalse) {
                val pIdx = normalizedOptions.indexOfFirst { it == "prawda" || it == "tak" }.coerceAtLeast(0)
                val fIdx = normalizedOptions.indexOfFirst { it == "fałsz" || it == "nie" }.coerceAtLeast(1)

                val subjectWords = cleanQ.split(Regex("[\\s,;\\.\\?!\\(\\)\"]+"))
                    .filter { it.length >= 4 && !stopwords.contains(it.lowercase()) }
                val subject = subjectWords.take(3).joinToString(" ")
                if (subject.isNotEmpty()) {
                    val extract = fetchWikiExtract(subject).lowercase()
                    if (extract.isNotEmpty()) {
                        val matchCount = subjectWords.count { extract.contains(it.lowercase()) }
                        if (matchCount >= 2) return listOf(pIdx)
                        if (extract.contains("nieprawda") || extract.contains("błędn") || extract.contains("fałsz")) return listOf(fIdx)
                    }
                }
            }

            // B. Ekstrakcja słów kluczowych pytania
            val p = Pattern.compile("[A-Za-zżźćńółęąśŻŹĆĄŚĘŁÓŃ]{4,}")
            val m = p.matcher(cleanQ)
            val qWords = mutableListOf<String>()
            while (m.find()) {
                val w = m.group().lowercase()
                if (!stopwords.contains(w)) {
                    qWords.add(w)
                }
            }

            if (qWords.isEmpty()) {
                logTelemetry("solver_semantic_empty_keywords", JSONObject().apply { put("cleanQ", cleanQ) })
                return emptyList()
            }

            // 1. Multi-zapytanie Wikipedii (unikamy zapytań powyżej 3 słów, które zwracają 0 wyników)
            val queryCandidates = mutableListOf<String>()
            if (qWords.size >= 2) queryCandidates.add("${qWords[0]} ${qWords[1]}")
            if (qWords.size >= 3) queryCandidates.add("${qWords[0]} ${qWords[1]} ${qWords[2]}")
            queryCandidates.add(qWords[0])

            val allTitles = mutableListOf<String>()
            val allSnippets = StringBuilder()

            for (qCandidate in queryCandidates) {
                val (titles, snippets) = searchWikipedia(qCandidate)
                for (t in titles) {
                    if (!allTitles.contains(t)) allTitles.add(t)
                }
                for (s in snippets) allSnippets.append(" ").append(s)
                if (allTitles.size >= 3) break
            }

            // 2. Pobranie pełnego artykułu dla pierwszych 2 trafień
            val articleTexts = StringBuilder()
            for (title in allTitles.take(2)) {
                val art = fetchWikiExtract(title)
                if (art.isNotEmpty()) {
                    articleTexts.append(" ").append(art)
                }
            }

            val combinedKnowledge = (allSnippets.toString() + " " + articleTexts.toString()).lowercase()

            val scores = IntArray(options.size)
            val scoreDetails = JSONArray()

            for (i in options.indices) {
                val opt = options[i].trim()
                val optLow = opt.lowercase()
                if (optLow.isEmpty()) continue

                var optScore = 0

                // A. Analiza wieków i cyfr rzymskich
                val romanMatch = Pattern.compile("^(i{1,3}|iv|v|vi{1,3}|ix|x)\\s*w").matcher(optLow)
                if (romanMatch.find()) {
                    val r = romanMatch.group(1) ?: ""
                    val centuryPattern = Pattern.compile("\\b$r\\s+(?:wiek[a-z]*|w\\.)")
                    val cm = centuryPattern.matcher(combinedKnowledge)
                    while (cm.find()) {
                        val pos = cm.start()
                        val window = combinedKnowledge.substring(maxOf(0, pos - 100), minOf(combinedKnowledge.length, pos + 100))
                        optScore += 20
                        if (window.contains("powsta") || window.contains("narodz") || window.contains("pocz") || window.contains("założ")) {
                            optScore += 45
                        }
                    }
                } else {
                    // B. Bezpośrednie dopasowanie pełnej opcji
                    if (combinedKnowledge.contains(optLow)) {
                        optScore += 40
                    }

                    // C. Dopasowanie rdzeni słów opcji (stemming)
                    val optWords = optLow.split(Regex("[\\s,;\\.\\?!\\(\\)\"]+")).filter { it.length >= 4 && !stopwords.contains(it) }
                    var stemHits = 0
                    for (ow in optWords) {
                        val stem = getStem(ow)
                        if (combinedKnowledge.contains(stem)) {
                            stemHits++
                        }
                    }
                    if (optWords.isNotEmpty() && stemHits == optWords.size) {
                        optScore += 35
                    } else {
                        optScore += stemHits * 12
                    }
                }

                // D. Ukierunkowane zapytanie per-opcja: "$option $klucz"
                if (optScore < 25 && qWords.isNotEmpty()) {
                    val topKey = qWords[0]
                    val (optTitles, optSnippets) = searchWikipedia("$opt $topKey")
                    if (optTitles.isNotEmpty()) {
                        val topArt = optTitles[0].lowercase()
                        if (topArt.contains(optLow) || optLow.contains(topArt)) {
                            optScore += 25
                        }
                        val sampleText = (optSnippets.joinToString(" ") + " " + fetchWikiExtract(optTitles[0])).lowercase()
                        for (qw in qWords) {
                            if (sampleText.contains(getStem(qw))) {
                                optScore += 8
                            }
                        }
                    }
                }

                scores[i] = optScore
                scoreDetails.put(JSONObject().apply {
                    put("optionIndex", i)
                    put("optionText", opt)
                    put("score", optScore)
                })
            }

            val maxScore = scores.maxOrNull() ?: 0
            val maxOccurrences = scores.count { it == maxScore }
            val isTie = (maxOccurrences > 1 && maxScore > 0)

            logTelemetry("solver_semantic_scored", JSONObject().apply {
                put("queryUsed", queryCandidates.firstOrNull() ?: "")
                put("titlesReturned", JSONArray(allTitles))
                put("scores", JSONArray(scores.toList()))
                put("maxScore", maxScore)
                put("isTie", isTie)
                put("scoreDetails", scoreDetails)
            })

            if (maxScore <= 0) {
                logTelemetry("solver_zero_consensus", JSONObject().apply {
                    put("reason", "all_options_scored_zero")
                })
                return emptyList()
            }

            if (isMultiple) {
                val selected = mutableListOf<Int>()
                for (i in scores.indices) {
                    if (scores[i] >= (maxScore * 0.50) && scores[i] >= 8) {
                        selected.add(i)
                    }
                }
                if (selected.isNotEmpty()) return selected
            }

            if (isTie) {
                val tiedIndices = scores.indices.filter { scores[it] == maxScore }
                var tieWinner = -1
                var highestHits = -1

                for (tIdx in tiedIndices) {
                    val opt = options[tIdx]
                    val (specTitles, _) = searchWikipedia("${qWords.firstOrNull() ?: ""} $opt")
                    val hits = specTitles.size
                    if (hits > highestHits) {
                        highestHits = hits
                        tieWinner = tIdx
                    }
                }

                if (tieWinner != -1 && highestHits > 0) {
                    logTelemetry("solver_tie_resolved", JSONObject().apply {
                        put("winnerIndex", tieWinner)
                        put("winnerText", options[tieWinner])
                    })
                    return listOf(tieWinner)
                }

                logTelemetry("solver_tie_unresolved_abort", JSONObject().apply {
                    put("tiedIndices", JSONArray(tiedIndices))
                })
                return emptyList()
            }

            val bestIdx = scores.indexOfFirst { it == maxScore }
            if (bestIdx != -1) return listOf(bestIdx)

            emptyList()
        } catch (e: Exception) {
            logTelemetry("solver_semantic_exception", JSONObject().apply { put("error", e.message ?: e.toString()) })
            emptyList()
        }
    }

    private fun searchWikipedia(query: String): Pair<List<String>, List<String>> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("https://pl.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", WIKI_USER_AGENT)
            conn.connectTimeout = 3500
            conn.readTimeout = 3500

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val results = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
                val titles = mutableListOf<String>()
                val snippets = mutableListOf<String>()
                for (i in 0 until minOf(results.length(), 4)) {
                    val item = results.getJSONObject(i)
                    titles.add(item.optString("title", ""))
                    snippets.add(item.optString("snippet", ""))
                }
                Pair(titles, snippets)
            } else Pair(emptyList(), emptyList())
        } catch (e: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    private fun fetchWikiExtract(title: String): String {
        return try {
            val encoded = URLEncoder.encode(title.trim(), "UTF-8")
            val url = URL("https://pl.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exchars=3500&titles=$encoded&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", WIKI_USER_AGENT)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return ""
                val firstKey = pages.keys().next() ?: return ""
                pages.getJSONObject(firstKey).optString("extract", "")
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractDynamicEntity(question: String): String {
        return try {
            val q = cleanQuestionText(question).lowercase()
            val words = q.split(Regex("[\\s,;\\.\\?!\\(\\)\"]+")).filter { it.length >= 4 }
            if (words.isNotEmpty()) {
                val (titles, _) = searchWikipedia(words.take(2).joinToString(" "))
                if (titles.isNotEmpty()) {
                    val extract = fetchWikiExtract(titles[0])
                    if (q.contains("rok") || q.contains("kiedy") || q.contains("którym roku")) {
                        val p = Pattern.compile("\\b(1[0-9]{3}|20[0-2][0-9]|[1-9][0-9]{1,2}(?:\\s*p\\.n\\.e\\.)?)\\b")
                        val m = p.matcher(extract)
                        if (m.find()) return m.group(1) ?: ""
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    fun parseIndicesFromText(text: String, options: List<String>, isMultiple: Boolean): List<Int> {
        val count = options.size
        if (count == 0) return emptyList()
        val result = mutableListOf<Int>()

        val bracketPattern = Pattern.compile("[\\[\\(]([0-9,\\s]+)[\\]\\)]")
        val bm = bracketPattern.matcher(text)
        while (bm.find()) {
            val group = bm.group(1) ?: continue
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

        val letterPattern = Pattern.compile("(?:opcj[ae]|indeks|odpowied[zź]|wariant|punkt)?\\s*[\\[\\(]?\\s*([A-Fa-f])\\s*[\\]\\)]?")
        val lm = letterPattern.matcher(text)
        while (lm.find()) {
            val letterChar = lm.group(1)?.uppercase()?.firstOrNull() ?: continue
            val idx = letterChar - 'A'
            if (idx in 0 until count && !result.contains(idx)) {
                result.add(idx)
            }
        }
        if (result.isNotEmpty()) {
            return if (isMultiple) result else listOf(result[0])
        }

        val lower = text.lowercase()
        options.forEachIndexed { i, opt ->
            val optClean = opt.trim().lowercase()
            if (optClean.isNotEmpty()) {
                val escaped = Pattern.quote(optClean)
                val wordMatch = Pattern.compile("\\b$escaped\\b").matcher(lower).find()
                if (wordMatch || (optClean.length >= 4 && lower.contains(optClean))) {
                    if (!result.contains(i)) result.add(i)
                }
            }
        }
        if (result.isNotEmpty()) {
            return if (isMultiple) result else listOf(result[0])
        }

        val digitPattern = Pattern.compile("\\b([0-9]+)\\b")
        val dm = digitPattern.matcher(text)
        while (dm.find()) {
            val digit = dm.group(1)?.toIntOrNull()
            if (digit != null && digit in 0 until count && !result.contains(digit)) {
                result.add(digit)
            }
        }

        return if (isMultiple && result.isNotEmpty()) result else if (result.isNotEmpty()) listOf(result[0]) else emptyList()
    }

    private fun parseOpenAnswer(text: String): String {
        var clean = text.replace("```json", "").replace("```", "").trim()
        if (clean.contains(":")) {
            val parts = clean.split(":")
            if (parts.size >= 2) {
                val candidate = parts[1].trim()
                if (candidate.isNotEmpty() && candidate.length < 150) {
                    clean = candidate
                }
            }
        }
        clean = clean.removeSurrounding("\"").removeSurrounding("'").trim()
        return clean
    }

    private fun isDummyPlaceholder(answer: String): Boolean {
        val l = answer.trim().lowercase()
        return l == "poprawnie" || l == "poprawna" || l == "odpowiedź" || l == "brak" || l.isEmpty()
    }

    private fun synthesizeDynamicOpen(question: String): String {
        val q = cleanQuestionText(question).lowercase()

        if (q.contains("średni") || q.contains("srednia")) {
            val nums = extractNumbers(question)
            if (nums.size >= 2) {
                val avg = nums.sum() / nums.size
                return if (avg == floor(avg)) avg.toInt().toString() else String.format("%.1f", avg)
            }
        }

        if (q.contains("ile") || q.contains("liczba")) {
            val nums = extractNumbers(question)
            if (nums.isNotEmpty()) return "${nums[0].toInt()}"
        }

        return ""
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

    private fun isError(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("<!doctype") || l.contains("404 not found") || l.contains("free limit") || l.contains("payment required") || l.contains("error:")
    }
}
