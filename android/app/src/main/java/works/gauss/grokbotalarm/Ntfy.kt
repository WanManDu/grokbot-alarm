package works.gauss.grokbotalarm

import java.net.HttpURLConnection
import java.net.URL

object Ntfy {
    fun publish(server: String, topic: String, message: String, priority: Int) {
        require(topic.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "bad topic" }
        val conn = URL(server.trimEnd('/') + "/" + topic).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        conn.setRequestProperty("Title", "grokbot-alarm")
        conn.setRequestProperty("Priority", priority.toString())
        conn.setRequestProperty("User-Agent", "grokbot-alarm/0.1")
        conn.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.close()
        conn.disconnect()
        if (code !in 200..299) error("ntfy $code")
    }
}
