package works.gauss.grokbotalarm

import java.net.URLDecoder

data class PairTarget(val secret: String, val server: String, val name: String)

private const val PAIR_HOST = "wanmandu.github.io"
private const val PAIR_PATH = "/grokbot-alarm/pair"

/**
 * Pairing data from the https page fragment, the custom scheme, or an intent URL.
 * Returns null when the secret or server is not usable.
 */
fun parsePairLink(raw: String): PairTarget? {
    val query = pairQuery(raw.trim()) ?: return null
    val fields = decodeForm(query)
    val secret = fields["s"] ?: return null
    val server = (fields["srv"] ?: return null).trim().trimEnd('/')
    val name = fields["n"].orEmpty().trim().replace(Regex("\\s+"), " ").take(40).ifBlank { "AI" }
    if (!validSecret(secret) || !validServer(server)) return null
    return PairTarget(secret, server, name)
}

private fun pairQuery(text: String): String? {
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    if (lower.startsWith("intent://") || lower.startsWith("grokbot-alarm://")) {
        val q = text.substringAfter("?", "")
        if (q.isEmpty() || q == text) return null
        return q.substringBefore("#")
    }
    if (lower.startsWith("https://") || lower.startsWith("http://")) {
        val beforeHash = text.substringBefore("#")
        val afterScheme = beforeHash.substringAfter("://", "")
        val host = afterScheme.substringBefore("/").substringBefore("?").lowercase()
        if (host != PAIR_HOST) return null
        val pathAndQuery = afterScheme.substringAfter("/", "")
        val path = "/" + pathAndQuery.substringBefore("?").trimEnd('/')
        if (path != PAIR_PATH) return null
        val fragment = text.substringAfter("#", "")
        if (fragment.isEmpty()) return null
        return fragment.substringBefore("#")
    }
    return null
}

private fun decodeForm(query: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    query.split("&").forEach { part ->
        if (part.isEmpty()) return@forEach
        val key = urlDecode(part.substringBefore("="))
        val value = urlDecode(part.substringAfter("=", ""))
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}

private fun urlDecode(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8.name())
