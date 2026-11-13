package works.gauss.grokbotalarm

import org.json.JSONArray
import org.json.JSONObject

data class PendingWake(val id: String, val bot: String, val task: String)

data class AlarmPayload(
    val id: String,
    val bot: String,
    val task: String,
    val question: String,
    val options: List<String>,
    val next: List<String>,
    val replyTopic: String,
    val test: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("bot", bot)
        .put("task", task)
        .put("question", question)
        .put("options", JSONArray(options))
        .put("next", JSONArray(next))
        .put("replyTopic", replyTopic)
        .put("test", test)
        .toString()

    companion object {
        fun fromJson(raw: String): AlarmPayload? = try {
            fromObject(JSONObject(raw), allowTest = true)
        } catch (_: Exception) {
            null
        }
    }
}

sealed interface Incoming {
    data class Register(val wake: PendingWake) : Incoming
    data class Alarm(val payload: AlarmPayload) : Incoming
}

private val topicName = Regex("^[A-Za-z0-9_-]{1,64}$")

fun parseIncoming(body: String): Incoming? = try {
    val obj = JSONObject(body)
    when (obj.optString("type")) {
        "register" -> {
            val wake = pendingFrom(obj) ?: return null
            Incoming.Register(wake)
        }
        "alarm" -> {
            val payload = fromObject(obj, allowTest = false) ?: return null
            Incoming.Alarm(payload)
        }
        else -> null
    }
} catch (_: Exception) {
    null
}

private fun pendingFrom(obj: JSONObject): PendingWake? {
    val id = obj.optString("id").trim()
    val bot = obj.optString("bot").trim()
    val task = obj.optString("task").trim()
    if (id.isEmpty() || bot.isEmpty() || task.isEmpty()) return null
    return PendingWake(id, bot, task)
}

private fun fromObject(obj: JSONObject, allowTest: Boolean): AlarmPayload? {
    val id = obj.optString("id").trim()
    val bot = obj.optString("bot").trim()
    val task = obj.optString("task").trim()
    val question = obj.optString("question").trim()
    val reply = obj.optString("replyTopic").trim()
    val test = obj.optBoolean("test", false)
    val options = stringList(obj.optJSONArray("options"))
    val next = stringList(obj.optJSONArray("next"))
    if (id.isEmpty() || bot.isEmpty() || task.isEmpty() || question.isEmpty() || options.isEmpty()) {
        return null
    }
    if (!test && !topicName.matches(reply)) return null
    if (test && !allowTest) return null
    return AlarmPayload(id, bot, task, question, options, next, reply, test)
}

private fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val value = array.optString(i).trim()
        if (value.isNotEmpty()) out.add(value)
    }
    return out
}
