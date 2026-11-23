package li.gkd.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class GithubCertificateProbeTest {
    @Test
    fun certificateBaselineMetadataRoundTrips() {
        val report =
            GithubCertificateReport(
                host = "api.github.com",
                sha256 = "AA:BB:CC",
                subject = "CN=api.github.com",
                issuer = "CN=Example CA",
                subjectAltNames = listOf("api.github.com", "*.github.com"),
                notBefore = "Mon Nov 23 00:00:00 UTC 2026",
                notAfter = "Tue Nov 23 00:00:00 UTC 2027",
            )
        val encoded = Json.encodeToString(report)
        assertEquals(report, Json.decodeFromString(encoded))
    }
}
