package li.gkd.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkEventLogTest {
    @Test
    fun requestDetailsStripQueryParametersAndKeepRequestMetadata() {
        NetworkEventLog.clear()
        NetworkEventLog.request(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo?token=private-value#fragment",
            status = 200,
        )

        val event = NetworkEventLog.events.value.last()
        assertEquals("github.com", event.host)
        assertEquals("GET", event.method)
        assertEquals("https://github.com/example/repo", event.requestUrl)
        assertEquals(200, event.statusCode)
        assertEquals("HTTP", event.source)
        assertTrue(event.detail.contains("HTTP 200"))
        assertFalse(event.detail.contains("private-value"))
        assertFalse(event.requestUrl.contains("fragment"))
    }

    @Test
    fun redirectLocationIsRecordedAndSensitiveQueryIsRedacted() {
        NetworkEventLog.clear()
        NetworkEventLog.request(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo",
            status = 302,
            redirectLocation = "https://raw.githubusercontent.com/example/repo/main/file.json?token=private-value",
            source = "IMAGE",
        )

        val event = NetworkEventLog.events.value.single()
        assertEquals("WARN", event.level)
        assertEquals("IMAGE", event.source)
        assertEquals(302, event.statusCode)
        assertEquals("https://raw.githubusercontent.com/example/repo/main/file.json", event.redirectTarget)
        assertFalse(event.message.contains("private-value"))
        assertFalse(event.toDiagnosticText().contains("private-value"))
        assertTrue(event.message.contains("Location:"))
    }

    @Test
    fun diagnosticExportIncludesSourceAndRedirectTarget() {
        NetworkEventLog.clear()
        NetworkEventLog.request(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/file",
            status = 302,
            redirectLocation = "https://raw.githubusercontent.com/example/file",
            source = "IMAGE",
        )
        val exported = NetworkEventLog.export()
        assertTrue(exported.contains("source=IMAGE"))
        assertTrue(exported.contains("http_status=302"))
        assertTrue(exported.contains("redirect_location=https://raw.githubusercontent.com/example/file"))
    }
}
