package works.gauss.grokbotalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) {
            sp.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).commit()
        }

    var lastId: String
        get() = sp.getString(KEY_LAST, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_LAST, value).commit()
        }

    /** One-time import of the single-secret build into the first Connected AI. */
    fun migrate() {
        if (sp.getBoolean(KEY_MIGRATED, false)) return
        val legacy = sp.getString(KEY_SECRET, "")?.trim().orEmpty()
        if (legacy.isNotBlank() && agents().isEmpty() && validSecret(legacy)) {
            val agent = Agent(newId(), "AI", legacy, 0L)
            val tasks = ArrayList<TaskItem>()
            legacyPending().forEach { wake ->
                tasks.add(
                    TaskItem(
                        id = wake.id,
                        agentId = agent.id,
                        bot = wake.bot,
                        task = wake.task,
                        state = STATE_WORKING,
                        registeredAt = System.currentTimeMillis(),
                    ),
                )
            }
            legacyOpen().forEach { payload ->
                if (isAnswered(payload.id)) return@forEach
                tasks.removeAll { it.id == payload.id }
                tasks.add(
                    TaskItem(
                        id = payload.id,
                        agentId = agent.id,
                        bot = payload.bot,
                        task = payload.task,
                        state = STATE_WAITING,
                        registeredAt = System.currentTimeMillis(),
                        question = payload.question,
                        options = payload.options,
                        next = payload.next,
                        replyTopic = payload.replyTopic,
                        ringing = true,
                    ),
                )
            }
            writeAgents(listOf(agent))
            writeTasks(tasks)
        }
        if (server.isBlank()) server = DEFAULT_SERVER
        sp.edit().putBoolean(KEY_MIGRATED, true).commit()
    }

    fun agents(): List<Agent> = readArray(KEY_AGENTS) { obj ->
        val id = obj.optString("id")
        val secret = obj.optString("secret")
        if (id.isEmpty() || !validSecret(secret)) null
        else Agent(id, obj.optString("name").ifBlank { "AI" }, secret, obj.optLong("lastSeen"))
    }

    fun tasks(): List<TaskItem> = readArray(KEY_TASKS) { obj ->
        val id = obj.optString("id")
        val agentId = obj.optString("agentId")
        if (id.isEmpty() || agentId.isEmpty()) null
        else TaskItem(
            id = id,
            agentId = agentId,
            bot = obj.optString("bot"),
            task = obj.optString("task"),
            state = obj.optString("state").ifBlank { STATE_WORKING },
            registeredAt = obj.optLong("registeredAt"),
            question = obj.optString("question"),
            options = jsonStrings(obj.optJSONArray("options")),
            next = jsonStrings(obj.optJSONArray("next")),
            replyTopic = obj.optString("replyTopic"),
            escalateAt = obj.optLong("escalateAt"),
            ringing = obj.optBoolean("ringing"),
        )
    }

    fun addAgent(name: String): Agent {
        val clean = name.trim().ifBlank { "AI" }.take(40)
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val agent = Agent(newId(), clean, bytes.joinToString("") { "%02x".format(it) }, 0L)
        writeAgents(agents() + agent)
        return agent
    }

    fun renameAgent(id: String, name: String) {
        val clean = name.trim().ifBlank { "AI" }.take(40)
        writeAgents(agents().map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun dropAgent(id: String) {
        writeAgents(agents().filter { it.id != id })
        writeTasks(tasks().filter { it.agentId != id })
    }

    fun touch(agentId: String) {
        val now = System.currentTimeMillis()
        writeAgents(agents().map { if (it.id == agentId) it.copy(lastSeen = now) else it })
    }

    fun agentForInbox(topic: String): Agent? = agents().find { inboxTopic(it.secret) == topic }

    fun agent(id: String): Agent? = agents().find { it.id == id }

    fun noteWorking(agentId: String, id: String, bot: String, task: String) {
        val current = tasks().toMutableList()
        val existing = current.indexOfFirst { it.id == id }
        if (existing >= 0) {
            val old = current[existing]
            if (old.state == STATE_WORKING) {
                current[existing] = old.copy(bot = bot, task = task)
                writeTasks(current)
            }
            return
        }
        current.add(
            TaskItem(
                id = id,
                agentId = agentId,
                bot = bot,
                task = task,
                state = STATE_WORKING,
                registeredAt = System.currentTimeMillis(),
            ),
        )
        writeTasks(trim(current))
    }

    fun sinceQuery(): String = lastId.ifBlank { "12h" }

    fun hasSeen(messageId: String): Boolean = messageId.isNotEmpty() && seen().contains(messageId)

    /** Remember a ntfy message so a reconnect cannot deliver it again. */
    fun markSeen(messageId: String, timeSec: Long) {
        if (messageId.isEmpty()) return
        val ids = seen().toMutableList()
        if (messageId !in ids) ids.add(messageId)
        while (ids.size > 500) ids.removeAt(0)
        val edit = sp.edit().putString(KEY_SEEN, JSONArray(ids).toString())
        if (timeSec > sp.getLong(KEY_LAST_TIME, 0L)) edit.putLong(KEY_LAST_TIME, timeSec)
        edit.putString(KEY_LAST, messageId).commit()
    }

    fun noteWaiting(agentId: String, payload: AlarmPayload, escalateAt: Long, ringing: Boolean): Boolean {
        if (isAnswered(payload.id)) return false
        val current = tasks().toMutableList()
        val existing = current.indexOfFirst { it.id == payload.id }
        if (existing >= 0 && current[existing].state == STATE_DONE) return false
        val registered = if (existing >= 0) current[existing].registeredAt else System.currentTimeMillis()
        val item = TaskItem(
            id = payload.id,
            agentId = agentId,
            bot = payload.bot,
            task = payload.task,
            state = STATE_WAITING,
            registeredAt = registered,
            question = payload.question,
            options = payload.options,
            next = payload.next,
            replyTopic = payload.replyTopic,
            escalateAt = escalateAt,
            ringing = ringing,
        )
        if (existing >= 0) current[existing] = item else current.add(item)
        writeTasks(trim(current))
        return true
    }

    fun isAnswered(id: String): Boolean = answered().contains(id)

    fun markDone(id: String) {
        val ids = answered().toMutableList()
        if (id !in ids) ids.add(id)
        while (ids.size > 500) ids.removeAt(0)
        val next = tasks().map { task ->
            if (task.id != id) task
            else task.copy(state = STATE_DONE, ringing = false, escalateAt = 0)
        }
        sp.edit().putString(KEY_ANSWERED, JSONArray(ids).toString()).commit()
        writeTasks(next)
    }

    private fun trim(items: List<TaskItem>): List<TaskItem> {
        if (items.size <= 40) return items
        val done = items.filter { it.state == STATE_DONE }
        val rest = items.filter { it.state != STATE_DONE }
        val keepDone = done.takeLast((40 - rest.size).coerceAtLeast(0))
        return (rest + keepDone).takeLast(40)
    }

    private fun writeAgents(items: List<Agent>) {
        val array = JSONArray()
        items.forEach { agent ->
            array.put(
                JSONObject()
                    .put("id", agent.id)
                    .put("name", agent.name)
                    .put("secret", agent.secret)
                    .put("lastSeen", agent.lastSeen),
            )
        }
        sp.edit().putString(KEY_AGENTS, array.toString()).commit()
    }

    private fun writeTasks(items: List<TaskItem>) {
        val array = JSONArray()
        items.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("agentId", task.agentId)
                    .put("bot", task.bot)
                    .put("task", task.task)
                    .put("state", task.state)
                    .put("registeredAt", task.registeredAt)
                    .put("question", task.question)
                    .put("options", JSONArray(task.options))
                    .put("next", JSONArray(task.next))
                    .put("replyTopic", task.replyTopic)
                    .put("escalateAt", task.escalateAt)
                    .put("ringing", task.ringing),
            )
        }
        sp.edit().putString(KEY_TASKS, array.toString()).commit()
    }

    private fun <T> readArray(key: String, map: (JSONObject) -> T?): List<T> {
        val raw = sp.getString(key, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    map(obj)?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun seen(): List<String> = stringList(KEY_SEEN)

    private fun answered(): List<String> = stringList(KEY_ANSWERED)

    private fun stringList(key: String): List<String> {
        val raw = sp.getString(key, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val id = array.optString(i)
                    if (id.isNotEmpty()) add(id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun legacyPending(): List<PendingLegacy> = readArray(KEY_PENDING) { obj ->
        val id = obj.optString("id")
        if (id.isEmpty()) null else PendingLegacy(id, obj.optString("bot"), obj.optString("task"))
    }

    private fun legacyOpen(): List<AlarmPayload> {
        val raw = sp.getString(KEY_OPEN, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    AlarmPayload.fromJson(obj.toString())?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun newId(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class PendingLegacy(val id: String, val bot: String, val task: String)

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private const val NAME = "grokbot-alarm"
        private const val KEY_SERVER = "server"
        private const val KEY_SECRET = "secret"
        private const val KEY_LAST = "last_id"
        private const val KEY_PENDING = "pending"
        private const val KEY_OPEN = "open"
        private const val KEY_ANSWERED = "answered"
        private const val KEY_SEEN = "seen_msgs"
        private const val KEY_LAST_TIME = "last_msg_time"
        private const val KEY_AGENTS = "agents"
        private const val KEY_TASKS = "tasks"
        private const val KEY_MIGRATED = "migrated_agents"
    }
}

fun validSecret(secret: String): Boolean {
    if (secret.length !in 16..40) return false
    return secret.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

fun validServer(server: String): Boolean {
    if (!(server.startsWith("https://") || server.startsWith("http://"))) return false
    return server.none { it.isWhitespace() } && server.substringAfter("://").isNotEmpty()
}

fun inboxTopic(secret: String) = "gba$secret"

fun replyTopic(secret: String) = "gbr$secret"

fun setupLine(name: String, secret: String, server: String): String {
    val safe = name.replace("\"", "").replace("'", "").trim().ifBlank { "AI" }
    return "Set up grokbot-alarm for me (phone alarm that wakes me when you need me). " +
        "Don't use ntfy directly, and don't invent topics. " +
        "Run: curl -fsSL https://raw.githubusercontent.com/WanManDu/grokbot-alarm/main/install.sh | sh -s -- " +
        "--secret $secret --server $server --agent \"$safe\". " +
        "Then read ~/.local/share/grokbot-alarm/SKILL.md and follow it whenever I ask to be woken or you need my decision. " +
        "If you can save a skill or a memory, save that file so you still have it next time."
}
