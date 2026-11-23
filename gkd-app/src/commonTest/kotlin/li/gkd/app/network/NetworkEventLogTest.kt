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
        assertTrue(event.detail.contains("HTTP 200"))
        assertFalse(event.detail.contains("private-value"))
        assertFalse(event.requestUrl.contains("fragment"))
    }
}
