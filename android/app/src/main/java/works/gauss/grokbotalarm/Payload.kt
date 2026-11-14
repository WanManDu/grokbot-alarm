package works.gauss.grokbotalarm

import org.json.JSONArray
import org.json.JSONObject

const val STATE_WORKING = "working"
const val STATE_WAITING = "waiting"
const val STATE_DONE = "done"

data class Agent(
    val id: String,
    val name: String,
    val secret: String,
    val lastSeen: Long,
)

data class TaskItem(
    val id: String,
    val agentId: String,
    val bot: String,
    val task: String,
    val state: String,
    val registeredAt: Long,
    val question: String = "",
    val options: List<String> = emptyList(),
    val next: List<String> = emptyList(),
    val replyTopic: String = "",
    val escalateAt: Long = 0,
    val ringing: Boolean = false,
) {
    fun toPayload(agentName: String): AlarmPayload = AlarmPayload(
        id = id,
        bot = bot,
        task = task,
        question = question,
        options = options,
        next = next,
        replyTopic = replyTopic,
        agent = agentName,
    )
}

data class AlarmPayload(
    val id: String,
    val bot: String,
    val task: String,
    val question: String,
    val options: List<String>,
    val next: List<String>,
    val replyTopic: String,
    val test: Boolean = false,
    val agent: String = "",
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
        .put("agent", agent)
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
    data class Register(val id: String, val bot: String, val task: String, val agent: String) : Incoming
    data class Needs(val payload: AlarmPayload, val escalateAfterSec: Int) : Incoming
    data class Alarm(val payload: AlarmPayload) : Incoming
}

private val topicName = Regex("^[A-Za-z0-9_-]{1,64}$")

fun parseIncoming(body: String): Incoming? = try {
    val obj = JSONObject(body)
    when (obj.optString("type")) {
        "register" -> {
            val id = obj.optString("id").trim()
            val bot = obj.optString("bot").trim()
            val task = obj.optString("task").trim()
            if (id.isEmpty() || bot.isEmpty() || task.isEmpty()) null
            else Incoming.Register(id, bot, task, obj.optString("agent").trim())
        }
        "needs" -> {
            val payload = fromObject(obj, allowTest = false) ?: return null
            Incoming.Needs(payload, obj.optInt("escalateAfter", 0).coerceAtLeast(0))
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
    return AlarmPayload(
        id, bot, task, question, options, next, reply, test, obj.optString("agent").trim(),
    )
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

fun agoLabel(lastSeen: Long, now: Long, listening: Boolean): String {
    if (lastSeen <= 0L) return "New"
    val seconds = ((now - lastSeen) / 1000).coerceAtLeast(0)
    if (listening && seconds < 90) return "Live"
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}
