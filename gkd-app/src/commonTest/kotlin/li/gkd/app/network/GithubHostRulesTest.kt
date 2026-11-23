package li.gkd.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class GithubHostRulesTest {
    @Test
    fun importsOnlyGithubRulesAndPreservesEmptySni() {
        val rules = GithubHostRules.parse(
            """
            [
              [["api.github.com"], "", "20.205.243.168"],
              [["example.com"], "example.com", "192.0.2.1"],
              [["*githubusercontent.com"], "github.com", "185.199.108.133"]
            ]
            """.trimIndent(),
        )
        assertEquals(2, rules.size)
        assertEquals("", rules.first { "api.github.com" in it.domains }.sni)
        assertEquals("20.205.243.168", rules.first { "api.github.com" in it.domains }.ip)
    }

    @Test
    fun matchesExactAndWildcardHosts() {
        val rules = GithubHostRules.parse(
            """[[["api.github.com"],"github.com","192.0.2.1"],[["*github.com"],"github.com","192.0.2.2"]]""",
        )
        assertEquals("192.0.2.1", GithubHostRules.find(rules, "api.github.com")?.ip)
        assertEquals("192.0.2.2", GithubHostRules.find(rules, "raw.github.com")?.ip)
        assertNull(GithubHostRules.find(rules, "github.example.com"))
        assertNull(GithubHostRules.find(rules, "notgithub.com"))
        val dottedWildcard = GithubHostRules.parse(
            """[[["*.github.com"],"github.com","192.0.2.3"]]""",
        )
        assertEquals("192.0.2.3", GithubHostRules.find(dottedWildcard, "api.github.com")?.ip)
        assertNull(GithubHostRules.find(dottedWildcard, "github.com"))
    }

    @Test
    fun roundTripsRulesAndRejectsInvalidGithubIp() {
        val original = listOf(GithubHostRule(listOf("api.github.com"), "", "192.0.2.1"))
        assertEquals(original, GithubHostRules.parse(GithubHostRules.encode(original)))
        assertFailsWith<IllegalArgumentException> {
            GithubHostRules.parse("""[[["api.github.com"],"","not-an-ip"]]""")
        }
    }

    @Test
    fun doesNotAcceptWildcardOutsideTheLeadingPosition() {
        assertFailsWith<IllegalArgumentException> {
            GithubHostRules.parse("""[[["api.*.github.com"],"","192.0.2.1"]]""")
        }
        assertTrue(GithubHostRules.parse("""[[["example.com"],"","invalid"]]""").isEmpty())
    }
}
