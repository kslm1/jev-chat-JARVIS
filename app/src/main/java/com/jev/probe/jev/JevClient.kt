package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct provider client:
 * - TypeSafe Jev: https://api.typesafe.ai/v1/systemone
 * - DeepSeek:     https://api.deepseek.com/chat/completions
 *
 * The two API keys are intentionally separate. Neither key is logged.
 */
class JevClient(
    private val typeSafeKey: String,
    private val deepSeekKey: String,
    private val replyModel: String
) {

    private val decisionsUrl = "https://api.typesafe.ai/v1/systemone"
    private val chatUrl = "https://api.deepseek.com/chat/completions"

    /** The 7 Jev judgment questions only. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        try {
            if (typeSafeKey.isBlank()) throw ProviderException("TypeSafe", "未设置 API Key")
            val body = JSONObject()
                .put("model", "jev-latest")
                .put("state", JevQuestions.buildState(snapshot, relationship))
                .put("questions", JevQuestions.judge())
            val answers = postJson(decisionsUrl, body, typeSafeKey, "TypeSafe")
                .optJSONObject("answers") ?: JSONObject()
            return Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            return Analysis(
                null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e)
            )
        }
    }

    /** Generate exactly 3 replies with the official DeepSeek API. */
    fun draftCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        if (deepSeekKey.isBlank()) throw ProviderException("DeepSeek", "未设置 API Key")
        return generateCandidates(snapshot, relationship)
    }

    /** Draft with DeepSeek, then rank the three replies with TypeSafe Jev. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = draftCandidates(snapshot, relationship)
        if (typeSafeKey.isBlank()) throw ProviderException("TypeSafe", "未设置 API Key")
        val questions = JSONObject().put(
            "best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply")
        )
        val body = JSONObject()
            .put("model", "jev-latest")
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val answers = postJson(decisionsUrl, body, typeSafeKey, "TypeSafe")
            .optJSONObject("answers") ?: JSONObject()
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /** Full end-to-end connectivity helper used by the settings screen. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try {
            draftAndRank(snapshot, relationship)
        } catch (e: Exception) {
            return a.copy(error = readableError(e))
        }
        return a.copy(rankedReplies = ranked)
    }

    private fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，直接输出 JSON 数组。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("temperature", 0.8)
            .put("max_tokens", 500)

        val resp = postJson(chatUrl, body, deepSeekKey, "DeepSeek")
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
        return parseThree(content)
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        val lines = content.split("\n")
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        return candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }.sortedByDescending { it.prob }
    }

    private fun postJson(
        urlStr: String,
        body: JSONObject,
        apiKey: String,
        provider: String
    ): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "JevAssistantDirect/1.3")
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 500 || code == 502 || code == 503 || code == 529) {
                    attempt++
                    if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) {
                    throw ProviderException(provider, "HTTP $code: ${text.take(220)}")
                }
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = if (e is ProviderException) e else ProviderException(provider, e.message ?: e.javaClass.simpleName)
                if (e.message?.contains("HTTP 4") == true) throw lastErr
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: ProviderException(provider, "request failed")
    }

    fun readableError(e: Exception): String {
        val provider = (e as? ProviderException)?.provider
        val m = e.message ?: e.javaClass.simpleName
        val prefix = provider?.let { "$it：" } ?: ""
        return when {
            m.contains("HTTP 401") || m.contains("HTTP 403") -> "${prefix}密钥无效或无权限"
            m.contains("HTTP 402") -> "${prefix}余额不足或计费受限"
            m.contains("HTTP 429") -> "${prefix}请求过于频繁"
            m.contains("HTTP 4") -> "${prefix}请求被拒：$m"
            m.contains("timed out", ignoreCase = true) || m.contains("timeout", ignoreCase = true) ->
                "${prefix}网络超时"
            m.contains("Unable to resolve host") || m.contains("Failed to connect") ->
                "${prefix}无法连接服务器"
            else -> "${prefix}$m"
        }
    }

    private class ProviderException(val provider: String, detail: String) :
        RuntimeException(detail)

    companion object { private const val TAG = "JEVASSIST" }
}
