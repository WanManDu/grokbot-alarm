package works.gauss.grokbotalarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val now = 1_700_000_000_000L
    private val nonce = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun topicDerivationIsOneWay() {
        val inbox = inboxTopic(secret)
        val reply = replyTopic(secret)
        assertEquals("gba9e83cc4424dae7054eabdf5dfc03c27d3aef5065", inbox)
        assertEquals("gbrc5e3dfde0c8881cba9f3d3672eec777832338dc1", reply)
        assertEquals(43, inbox.length)
        assertFalse(inbox.contains(secret))
        assertFalse(reply.contains(secret))
        assertNotEquals("gba$secret", inbox)
        assertNotEquals(inbox, reply)
        assertEquals("gba47954980ebaa668a469cca21e303722ee2bfb94c", inboxTopic(secret.dropLast(1) + "0"))
    }

    @Test
    fun sealMatchesThePythonVector() {
        val plain = "{\"v\":2,\"type\":\"hello\",\"agent\":\"coder\"}"
        val wire = seal(secret, plain, nonce, now)
        assertEquals(
            "{\"v\":2,\"t\":1700000000000,\"n\":\"AQIDBAUGBwgJCgsMDQ4PEA\"," +
                "\"c\":\"ZQSaCqI9-DHbsSkrCR42FKry2eV2x0TaTOik6UPWQ6PudBy6bkg\"," +
                "\"m\":\"CtsVsNkvQrBRvRYDYrmQb35kDKQTKlWQ6d_2-R7qKiw\"}",
            wire,
        )
        val opened = openEnvelope(secret, wire, now)
        assertEquals("coder", opened?.payload?.getString("agent"))
        assertEquals(nonce.toHex(), opened?.nonce)
    }

    @Test
    fun rejectsTamperedMacCiphertextAndWrongKey() {
        val wire = seal(secret, "{\"v\":2,\"type\":\"hello\"}", nonce, now)
        val flippedCipher = JSONObject(wire)
        val cipher = b64d(flippedCipher.getString("c"))
        cipher[0] = (cipher[0].toInt() xor 0x01).toByte()
        flippedCipher.put("c", b64e(cipher))
        assertNull(openEnvelope(secret, flippedCipher.toString(), now))

        val flippedMac = JSONObject(wire)
        val mac = b64d(flippedMac.getString("m"))
        mac[0] = (mac[0].toInt() xor 0x01).toByte()
        flippedMac.put("m", b64e(mac))
        assertNull(openEnvelope(secret, flippedMac.toString(), now))

        assertNull(openEnvelope("ffffffffffffffffffffffffffffffff", wire, now))
        assertNull(openEnvelope(secret, "{\"v\":1,\"type\":\"alarm\",\"id\":\"x\"}", now))
    }

    @Test
    fun rejectsStaleFutureAndReplayedNonce() {
        val fresh = seal(secret, "{\"v\":2,\"type\":\"hello\"}", nonce, now)
        val stale = seal(secret, "{\"v\":2,\"type\":\"hello\"}", ByteArray(16) { (it + 2).toByte() }, now - MAX_AGE_MS - 1)
        val future = seal(secret, "{\"v\":2,\"type\":\"hello\"}", ByteArray(16) { (it + 3).toByte() }, now + MAX_FUTURE_MS + 1)
        assertNull(openEnvelope(secret, stale, now))
        assertNull(openEnvelope(secret, future, now))
        val opened = openEnvelope(secret, fresh, now)
        assertTrue(opened != null)
        val window = ReplayWindow()
        assertTrue(window.accept(opened!!.nonce, opened.timestamp, now))
        assertFalse(window.accept(opened.nonce, opened.timestamp, now))
        val tiny = ReplayWindow(maxSize = 1)
        assertTrue(tiny.accept("aa", now, now))
        assertFalse(tiny.accept("bb", now, now))
    }

    @Test
    fun forgedReplyTopicIsIgnored() {
        assertEquals(replyTopic(secret), replyDestination(secret, "attacker"))
        assertNotEquals("attacker", replyDestination(secret, "attacker"))
        val inner = JSONObject()
            .put("v", 2)
            .put("type", "alarm")
            .put("id", "abc")
            .put("bot", "coder")
            .put("task", "Ship")
            .put("question", "Ship?")
            .put("options", org.json.JSONArray(listOf("Yes", "No")))
            .put("next", org.json.JSONArray())
            .put("replyTopic", "attacker")
            .toString()
        val wire = seal(secret, inner, ByteArray(16) { (it + 4).toByte() }, now)
        val opened = openEnvelope(secret, wire, now)
        assertTrue(opened != null)
        assertFalse(opened!!.payload.has("replyTopic"))
        val incoming = parseIncomingObject(opened.payload)
        val alarm = (incoming as Incoming.Alarm).payload
        val fixed = adoptReply(secret, alarm)
        assertEquals(replyTopic(secret), fixed.replyTopic)
        assertNotEquals("attacker", fixed.replyTopic)
    }

    @Test
    fun rateLimitIsPerAgentAndCaps() {
        val gate = RateGate(maxEvents = 8, windowMs = 60_000, maxRinging = 4)
        val start = 1_000_000L
        repeat(8) { assertTrue(gate.allow("a", start)) }
        assertFalse(gate.allow("a", start + 1_000))
        assertTrue(gate.allow("b", start))
        assertFalse(gate.allow("a", start + 60_000))
        assertTrue(gate.allow("a", start + 60_001))
        assertTrue(gate.underRingingCap(3))
        assertFalse(gate.underRingingCap(4))
        assertTrue(gate.underOpenCap(7))
        assertFalse(gate.underOpenCap(8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun b64e(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun b64d(text: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(text)
}
