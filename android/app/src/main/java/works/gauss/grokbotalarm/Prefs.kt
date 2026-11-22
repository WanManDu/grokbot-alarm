package works.gauss.grokbotalarm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class Prefs(context: Context) {
    private val sp = openStore(context.applicationContext)

    var server: String
        get() = sp.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) {
            writeServer(value, lock = true)
        }

    fun serverLocked(): Boolean = sp.getBoolean(KEY_SERVER_LOCKED, false)

    private fun writeServer(value: String, lock: Boolean) {
        val edit = sp.edit().putString(KEY_SERVER, value.trim().trimEnd('/'))
        if (lock) edit.putBoolean(KEY_SERVER_LOCKED, true)
        edit.commit()
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
        if (server.isBlank()) writeServer(DEFAULT_SERVER, lock = false)
        if (agents().isNotEmpty()) {
            sp.edit().putBoolean(KEY_SERVER_LOCKED, true).commit()
        }
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

    /**
     * Save an AI the bot already created. Same secret returns the existing AI.
     * An unlocked phone adopts [server]. A locked phone keeps its server unless
     * [allowServerChange] is true, which is only the explicit "use this host" tap.
     */
    fun connectPaired(
        name: String,
        secret: String,
        server: String,
        allowServerChange: Boolean = false,
    ): Agent {
        val cleanName = name.trim().ifBlank { "AI" }.take(40)
        val cleanServer = server.trim().trimEnd('/')
        val existing = agents().find { it.secret == secret }
        if (validServer(cleanServer)) {
            if (!serverLocked()) {
                writeServer(cleanServer, lock = true)
            } else if (allowServerChange && cleanServer != this.server) {
                writeServer(cleanServer, lock = true)
            }
        }
        if (existing != null) {
            if (existing.lastSeen <= 0L && existing.name != cleanName) {
                renameAgent(existing.id, cleanName)
            }
            return agent(existing.id) ?: existing
        }
        val created = Agent(newId(), cleanName, secret, 0L)
        writeAgents(agents() + created)
        return created
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

    fun hasSeen(messageId: String): Boolean =
        messageId.isNotEmpty() && seenEntries().any { it.first == messageId }

    /** Remember a ntfy message so a reconnect cannot deliver it again. */
    fun markSeen(messageId: String, timeSec: Long) {
        if (messageId.isEmpty()) return
        val nowMs = if (timeSec > 0) timeSec * 1000 else System.currentTimeMillis()
        val kept = seenEntries()
            .filter { it.second >= nowMs - MAX_AGE_MS && it.first != messageId }
            .toMutableList()
        kept.add(messageId to nowMs)
        while (kept.size > 2000) kept.removeAt(0)
        val array = JSONArray()
        kept.forEach { (id, stamp) -> array.put(JSONObject().put("id", id).put("t", stamp)) }
        val edit = sp.edit().putString(KEY_SEEN, array.toString())
        if (timeSec > sp.getLong(KEY_LAST_TIME, 0L)) edit.putLong(KEY_LAST_TIME, timeSec)
        edit.putString(KEY_LAST, messageId).commit()
    }

    /**
     * True the first time this authenticated nonce is seen inside the time window.
     * A full window of unexpired nonces fails closed instead of forgetting one.
     */
    fun acceptNonce(nonce: String, timeMs: Long, nowMs: Long): Boolean {
        val window = ReplayWindow()
        window.load(readNonces())
        val ok = window.accept(nonce, timeMs, nowMs)
        writeNonces(window.entries())
        return ok
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

    fun isAnswered(id: String): Boolean {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return answeredEntries().any { it.first == id && it.second >= cutoff }
    }

    fun markDone(id: String) {
        val now = System.currentTimeMillis()
        val kept = answeredEntries()
            .filter { it.second >= now - MAX_AGE_MS && it.first != id }
            .toMutableList()
        kept.add(id to now)
        while (kept.size > 2000) kept.removeAt(0)
        val array = JSONArray()
        kept.forEach { (answeredId, stamp) -> array.put(JSONObject().put("id", answeredId).put("t", stamp)) }
        val next = tasks().map { task ->
            if (task.id != id) task
            else task.copy(state = STATE_DONE, ringing = false, escalateAt = 0)
        }
        sp.edit().putString(KEY_ANSWERED, array.toString()).commit()
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

    private fun seenEntries(): List<Pair<String, Long>> = timedEntries(KEY_SEEN, "id")

    private fun answeredEntries(): List<Pair<String, Long>> = timedEntries(KEY_ANSWERED, "id")

    private fun readNonces(): List<Pair<String, Long>> = timedEntries(KEY_NONCES, "n")

    private fun writeNonces(entries: List<Pair<String, Long>>) {
        val array = JSONArray()
        entries.forEach { (nonce, stamp) -> array.put(JSONObject().put("n", nonce).put("t", stamp)) }
        sp.edit().putString(KEY_NONCES, array.toString()).commit()
    }

    /** Legacy builds stored a JSON array of id strings. Those have no timestamp and are dropped. */
    private fun timedEntries(key: String, idField: String): List<Pair<String, Long>> {
        val raw = sp.getString(key, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString(idField)
                    if (id.isEmpty() || !obj.has("t")) continue
                    add(id to obj.getLong("t"))
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
        private const val ENCRYPTED_NAME = "grokbot-alarm-enc"
        private const val LEGACY_NAME = "grokbot-alarm"
        private const val KEY_SERVER = "server"
        private const val KEY_SERVER_LOCKED = "server_locked"
        private const val KEY_SECRET = "secret"
        private const val KEY_LAST = "last_id"
        private const val KEY_PENDING = "pending"
        private const val KEY_OPEN = "open"
        private const val KEY_ANSWERED = "answered"
        private const val KEY_SEEN = "seen_msgs"
        private const val KEY_NONCES = "nonces"
        private const val KEY_LAST_TIME = "last_msg_time"
        private const val KEY_AGENTS = "agents"
        private const val KEY_TASKS = "tasks"
        private const val KEY_MIGRATED = "migrated_agents"
        private const val KEY_PLAINTEXT_MIGRATED = "plaintext_migrated"

        private fun openStore(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migratePlaintext(context, encrypted)
            return encrypted
        }

        private fun migratePlaintext(context: Context, dest: SharedPreferences) {
            val plain = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
            if (plain.all.isEmpty()) return
            if (!dest.getBoolean(KEY_PLAINTEXT_MIGRATED, false)) {
                val edit = dest.edit()
                for ((key, value) in plain.all) {
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Long -> edit.putLong(key, value)
                        is Int -> edit.putInt(key, value)
                        is Float -> edit.putFloat(key, value)
                    }
                }
                edit.putBoolean(KEY_PLAINTEXT_MIGRATED, true).commit()
            }
            plain.edit().clear().commit()
        }
    }
}

fun validSecret(secret: String): Boolean {
    if (secret.length !in 16..40) return false
    return secret.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

fun validServer(server: String): Boolean {
    if (server.any { it.isWhitespace() }) return false
    val https = server.startsWith("https://")
    val http = server.startsWith("http://")
    if (!https && !http) return false
    val rest = server.substringAfter("://", "")
    if (rest.isEmpty() || rest.startsWith("/") || rest.startsWith("@")) return false
    val authority = rest.substringBefore("/").substringBefore("?")
    if ('@' in authority) return false
    if (http) {
        val host = if (authority.startsWith("[")) {
            authority.substringAfter("[").substringBefore("]").lowercase()
        } else {
            authority.substringBefore(":").lowercase()
        }
        if (host != "localhost" && host != "127.0.0.1" && host != "::1") return false
    }
    return authority.isNotEmpty()
}

fun serverHost(server: String): String {
    val rest = server.substringAfter("://", server)
    return rest.substringBefore("/").substringBefore("?")
}

fun setupLine(name: String, secret: String, server: String): String {
    val safe = name.replace("\"", "").replace("'", "").trim().ifBlank { "AI" }
    return "Set up grokbot-alarm for me (phone alarm that wakes me when you need me). " +
        "Don't use ntfy directly, and don't invent topics. " +
        "Clone https://github.com/WanManDu/grokbot-alarm, check out the pinned commit in BOT.md, " +
        "run sha256sum -c checksums.sha256, then sh install.sh " +
        "--secret $secret --server $server --agent \"$safe\". " +
        "Then read ~/.local/share/grokbot-alarm/SKILL.md and follow it whenever I ask to be woken or you need my decision. " +
        "If you can save a skill or a memory, save that file so you still have it next time."
}
