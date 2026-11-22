package works.gauss.grokbotalarm

import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol v2. Keep this in step with cli/grokbot_alarm.py.
 *
 * Topics are HMAC(secret, label) and do not contain the secret. Messages are
 * encrypted with HMAC-SHA256 in counter mode and authenticated with a second
 * HMAC (encrypt-then-MAC). A timestamp outside [now - 24h, now + 5min] is
 * rejected. Callers record the nonce so a replay of a still-fresh message dies
 * even after the ntfy id cache forgets it.
 *
 * The reply topic is always [replyTopic]. A payload field cannot choose it.
 */

const val PROTOCOL = 2
const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
const val MAX_FUTURE_MS = 5L * 60L * 1000L
const val RATE_MAX_EVENTS = 8
const val RATE_WINDOW_MS = 60_000L
const val RATE_MAX_RINGING = 4
const val RATE_MAX_OPEN = 8

private const val NONCE_LEN = 16
private const val TOPIC_MAC_LEN = 20
private const val MAX_CIPHER = 8192
private val HKDF_SALT = "grokbot-alarm.v2".toByteArray(Charsets.UTF_8)
private val MAC_PREFIX = "gba2".toByteArray(Charsets.UTF_8)

data class Opened(val payload: JSONObject, val nonce: String, val timestamp: Long)

fun inboxTopic(secret: String): String = topic(secret, "inbox", "gba")

fun replyTopic(secret: String): String = topic(secret, "reply", "gbr")

/** Ignores [claimed]. The destination is derived from the secret alone. */
@Suppress("UNUSED_PARAMETER")
fun replyDestination(secret: String, claimed: String? = null): String {
    return replyTopic(secret)
}

fun adoptReply(secret: String, payload: AlarmPayload): AlarmPayload {
    return payload.copy(replyTopic = replyTopic(secret))
}

private fun topic(secret: String, role: String, prefix: String): String {
    val mac = hmac(secret.toByteArray(Charsets.UTF_8), "grokbot-alarm.v2.topic.$role".toByteArray(Charsets.UTF_8))
    return prefix + mac.copyOf(TOPIC_MAC_LEN).toHex()
}

fun seal(secret: String, plaintext: String, nonce: ByteArray = randomNonce(), nowMs: Long = System.currentTimeMillis()): String {
    require(nonce.size == NONCE_LEN)
    require(nowMs >= 0)
    val raw = plaintext.toByteArray(Charsets.UTF_8)
    require(raw.size <= MAX_CIPHER)
    val (encKey, macKey) = keys(secret)
    val ciphertext = xor(raw, keystream(encKey, nonce, raw.size))
    val mac = hmac(macKey, MAC_PREFIX + nonce + nowMs.toBytes8() + ciphertext)
    return "{\"v\":2,\"t\":$nowMs,\"n\":\"${b64e(nonce)}\",\"c\":\"${b64e(ciphertext)}\",\"m\":\"${b64e(mac)}\"}"
}

fun openEnvelope(secret: String, wire: String, nowMs: Long): Opened? {
    val obj = try {
        JSONObject(wire)
    } catch (_: Exception) {
        return null
    }
    if (obj.optInt("v", -1) != PROTOCOL) return null
    if (!obj.has("t")) return null
    val stamp = try {
        obj.getLong("t")
    } catch (_: Exception) {
        return null
    }
    if (stamp < 0 || stamp > nowMs + MAX_FUTURE_MS || stamp < nowMs - MAX_AGE_MS) return null
    val nonceText = obj.optString("n")
    val cipherText = obj.optString("c")
    val macText = obj.optString("m")
    if (nonceText.isEmpty() || macText.isEmpty()) return null
    if (listOf(nonceText, cipherText, macText).any { text -> text.any { it.isWhitespace() } }) return null
    val nonce = try {
        b64d(nonceText)
    } catch (_: Exception) {
        return null
    }
    val ciphertext = try {
        b64d(cipherText)
    } catch (_: Exception) {
        return null
    }
    val mac = try {
        b64d(macText)
    } catch (_: Exception) {
        return null
    }
    if (nonce.size != NONCE_LEN || mac.size != 32 || ciphertext.size > MAX_CIPHER) return null
    val (encKey, macKey) = keys(secret)
    val expected = hmac(macKey, MAC_PREFIX + nonce + stamp.toBytes8() + ciphertext)
    if (!MessageDigest.isEqual(expected, mac)) return null
    val plain = xor(ciphertext, keystream(encKey, nonce, ciphertext.size))
    val payload = try {
        JSONObject(String(plain, Charsets.UTF_8))
    } catch (_: Exception) {
        return null
    }
    payload.remove("replyTopic")
    return Opened(payload, nonce.toHex(), stamp)
}

class ReplayWindow(private val maxSize: Int = 4096) {
    private val seen = LinkedHashMap<String, Long>()

    fun prune(nowMs: Long) {
        val cutoff = nowMs - MAX_AGE_MS
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }

    fun accept(nonce: String, timestampMs: Long, nowMs: Long): Boolean {
        prune(nowMs)
        if (timestampMs > nowMs + MAX_FUTURE_MS || timestampMs < nowMs - MAX_AGE_MS) return false
        if (seen.containsKey(nonce)) return false
        if (seen.size >= maxSize) return false
        seen[nonce] = timestampMs
        return true
    }

    fun load(entries: List<Pair<String, Long>>) {
        entries.forEach { (nonce, stamp) -> seen[nonce] = stamp }
    }

    fun entries(): List<Pair<String, Long>> = seen.map { it.key to it.value }
}

class RateGate(
    private val maxEvents: Int = RATE_MAX_EVENTS,
    private val windowMs: Long = RATE_WINDOW_MS,
    private val maxRinging: Int = RATE_MAX_RINGING,
    private val maxOpen: Int = RATE_MAX_OPEN,
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    fun allow(agentId: String, nowMs: Long): Boolean {
        val window = hits.getOrPut(agentId) { ArrayDeque() }
        val cutoff = nowMs - windowMs
        while (window.isNotEmpty() && window.first() < cutoff) window.removeFirst()
        if (window.size >= maxEvents) return false
        window.addLast(nowMs)
        return true
    }

    fun underRingingCap(ringingNow: Int): Boolean = ringingNow < maxRinging

    fun underOpenCap(openNow: Int): Boolean = openNow < maxOpen
}

private fun keys(secret: String): Pair<ByteArray, ByteArray> {
    val prk = hmac(HKDF_SALT, secret.toByteArray(Charsets.UTF_8))
    return hkdfExpand(prk, "enc".toByteArray(Charsets.UTF_8), 32) to
        hkdfExpand(prk, "mac".toByteArray(Charsets.UTF_8), 32)
}

private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val out = ArrayList<Byte>(length)
    var prev = ByteArray(0)
    var counter = 1
    while (out.size < length) {
        prev = hmac(prk, prev + info + byteArrayOf(counter.toByte()))
        prev.forEach { out.add(it) }
        counter += 1
        if (counter > 255) error("hkdf")
    }
    return out.take(length).toByteArray()
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun keystream(key: ByteArray, nonce: ByteArray, size: Int): ByteArray {
    val out = ArrayList<Byte>(size)
    var counter = 0
    while (out.size < size) {
        hmac(key, nonce + counter.toBytes4()).forEach { out.add(it) }
        counter += 1
        if (counter > 0x10000) error("message too large")
    }
    return out.take(size).toByteArray()
}

private fun xor(left: ByteArray, right: ByteArray): ByteArray {
    val out = ByteArray(left.size)
    for (i in left.indices) {
        out[i] = (left[i].toInt() xor right[i].toInt()).toByte()
    }
    return out
}

private fun randomNonce(): ByteArray {
    val bytes = ByteArray(NONCE_LEN)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes
}

private fun b64e(data: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(data)

private fun b64d(text: String): ByteArray =
    Base64.getUrlDecoder().decode(text)

private fun Long.toBytes8(): ByteArray {
    val out = ByteArray(8)
    var value = this
    for (i in 7 downTo 0) {
        out[i] = value.toByte()
        value = value ushr 8
    }
    return out
}

private fun Int.toBytes4(): ByteArray {
    val out = ByteArray(4)
    var value = this
    for (i in 3 downTo 0) {
        out[i] = value.toByte()
        value = value ushr 8
    }
    return out
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}
