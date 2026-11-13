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

    var secret: String
        get() = sp.getString(KEY_SECRET, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_SECRET, value.trim()).commit()
        }

    var lastId: String
        get() = sp.getString(KEY_LAST, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_LAST, value).commit()
        }

    fun ensureSecret() {
        if (secret.isBlank()) {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            secret = bytes.joinToString("") { "%02x".format(it) }
        }
        if (server.isBlank()) server = DEFAULT_SERVER
    }

    /** Drop in-flight wakes when the pairing changes so an old topic cannot ring. */
    fun savePairing(newServer: String, newSecret: String) {
        val changed = server != newServer.trim().trimEnd('/') || secret != newSecret.trim()
        server = newServer
        secret = newSecret
        if (changed) {
            sp.edit()
                .putString(KEY_LAST, "")
                .putString(KEY_PENDING, "[]")
                .putString(KEY_OPEN, "[]")
                .commit()
        }
    }

    fun pending(): List<PendingWake> {
        val raw = sp.getString(KEY_PENDING, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val bot = obj.optString("bot")
                    val task = obj.optString("task")
                    if (id.isNotEmpty()) add(PendingWake(id, bot, task))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addPending(wake: PendingWake) {
        val next = pending().filter { it.id != wake.id }.takeLast(19) + wake
        writePending(next)
    }

    fun removePending(id: String) {
        writePending(pending().filter { it.id != id })
    }

    fun openAlarms(): List<AlarmPayload> {
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

    fun setOpen(alarms: List<AlarmPayload>) {
        val array = JSONArray()
        alarms.take(8).forEach { array.put(JSONObject(it.toJson())) }
        sp.edit().putString(KEY_OPEN, array.toString()).commit()
    }

    fun isAnswered(id: String): Boolean = answered().contains(id)

    fun markAnswered(id: String) {
        val ids = answered().toMutableList()
        if (id !in ids) ids.add(id)
        while (ids.size > 200) ids.removeAt(0)
        val open = JSONArray()
        openAlarms().filter { it.id != id }.take(8).forEach { open.put(JSONObject(it.toJson())) }
        sp.edit()
            .putString(KEY_ANSWERED, JSONArray(ids).toString())
            .putString(KEY_OPEN, open.toString())
            .commit()
    }

    private fun answered(): List<String> {
        val raw = sp.getString(KEY_ANSWERED, "[]") ?: "[]"
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

    private fun writePending(items: List<PendingWake>) {
        val array = JSONArray()
        items.forEach { wake ->
            array.put(
                JSONObject()
                    .put("id", wake.id)
                    .put("bot", wake.bot)
                    .put("task", wake.task),
            )
        }
        sp.edit().putString(KEY_PENDING, array.toString()).commit()
    }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private const val NAME = "grokbot-alarm"
        private const val KEY_SERVER = "server"
        private const val KEY_SECRET = "secret"
        private const val KEY_LAST = "last_id"
        private const val KEY_PENDING = "pending"
        private const val KEY_OPEN = "open"
        private const val KEY_ANSWERED = "answered"
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
