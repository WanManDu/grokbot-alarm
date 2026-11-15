package works.gauss.grokbotalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val link =
        "https://wanmandu.github.io/grokbot-alarm/pair#s=$secret&srv=https%3A%2F%2Fntfy.sh&n=Coder%20One"

    @Test
    fun fragmentRoundTrip() {
        val got = parsePairLink(link)
        assertEquals(secret, got?.secret)
        assertEquals("https://ntfy.sh", got?.server)
        assertEquals("Coder One", got?.name)
    }

    @Test
    fun secretStaysOutOfThePath() {
        assertEquals(false, link.substringBefore("#").contains(secret))
    }

    @Test
    fun customSchemeAndIntent() {
        val query = "s=$secret&srv=http%3A%2F%2F127.0.0.1%3A8080&n=Coder%20One"
        val scheme = parsePairLink("grokbot-alarm://pair?$query")
        val intent = parsePairLink(
            "intent://pair?$query#Intent;scheme=grokbot-alarm;package=works.gauss.grokbotalarm;end",
        )
        assertEquals("http://127.0.0.1:8080", scheme?.server)
        assertEquals("Coder One", scheme?.name)
        assertEquals(scheme, intent)
    }

    @Test
    fun plusDecodesAsSpace() {
        val got = parsePairLink(
            "grokbot-alarm://pair?s=$secret&srv=https%3A%2F%2Fntfy.sh&n=Coder+One",
        )
        assertEquals("Coder One", got?.name)
    }

    @Test
    fun rejectsAForeignHostAndAShortSecret() {
        assertNull(parsePairLink("https://example.com/grokbot-alarm/pair#s=$secret&srv=https%3A%2F%2Fntfy.sh&n=A"))
        assertNull(parsePairLink("https://wanmandu.github.io/grokbot-alarm/pair#s=short&srv=https%3A%2F%2Fntfy.sh&n=A"))
        assertNull(parsePairLink("https://wanmandu.github.io/other#s=$secret&srv=https%3A%2F%2Fntfy.sh"))
    }
}
