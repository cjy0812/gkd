package li.gkd.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SSLHandshakeException(message: String) : IllegalStateException(message)

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

    @Test
    fun redirectResponsesShareRequestIdAndIncrementHop() {
        NetworkEventLog.clear()
        val requestId = NetworkEventLog.newRequestId()
        val startedAt = System.currentTimeMillis()
        NetworkEventLog.requestStarted(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo/raw/main/file.json",
            requestId = requestId,
            source = "IMAGE",
        )
        NetworkEventLog.response(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo/raw/main/file.json",
            status = 302,
            redirectLocation = "https://raw.githubusercontent.com/example/repo/main/file.json?token=secret",
            requestId = requestId,
            startedAt = startedAt,
            source = "IMAGE",
        )
        NetworkEventLog.response(
            host = "raw.githubusercontent.com",
            method = "GET",
            url = "https://raw.githubusercontent.com/example/repo/main/file.json",
            status = 200,
            redirectLocation = null,
            requestId = requestId,
            startedAt = startedAt,
            source = "IMAGE",
        )

        val chain = NetworkEventLog.events.value.filter { it.requestId == requestId }
        assertEquals(listOf(1, 2), chain.mapNotNull { it.hop })
        assertTrue(chain.all { it.redirectChainId == requestId })
        assertTrue(chain.all { it.source == "IMAGE" })
        assertEquals("REDIRECT_RESPONSE", chain[1].phase)
        assertEquals("HTTP_RESPONSE", chain[2].phase)
        assertFalse(chain[1].redirectLocation.orEmpty().contains("secret"))
    }

    @Test
    fun failedRequestKeepsExceptionCauseChainAndRequestIdentity() {
        NetworkEventLog.clear()
        val requestId = NetworkEventLog.newRequestId()
        val startedAt = System.currentTimeMillis()
        NetworkEventLog.requestStarted(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo",
            requestId = requestId,
        )
        NetworkEventLog.requestFailed(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo",
            error = IllegalStateException("network timeout", IllegalStateException("connection refused")),
            requestId = requestId,
            startedAt = startedAt,
        )

        val event = NetworkEventLog.events.value.last()
        assertEquals(requestId, event.requestId)
        assertEquals("REQUEST_FAILED", event.phase)
        assertEquals("IllegalStateException", event.errorType)
        assertTrue(event.errorChain.orEmpty().contains("connection refused"))
        assertTrue(event.durationMs != null)
    }

    @Test
    fun tlsExceptionsAreClassifiedSeparatelyFromTransportFailures() {
        NetworkEventLog.clear()
        val requestId = NetworkEventLog.newRequestId()
        NetworkEventLog.requestFailed(
            host = "github.com",
            method = "GET",
            url = "https://github.com/example/repo",
            error = IllegalStateException("request failed", SSLHandshakeException("certificate rejected")),
            requestId = requestId,
            startedAt = System.currentTimeMillis(),
        )

        val event = NetworkEventLog.events.value.last()
        assertEquals("TLS_HANDSHAKE_FAILED", event.phase)
        assertTrue(event.errorChain.orEmpty().contains("certificate rejected"))
    }

    @Test
    fun ktorRedirectPluginLogsIntermediateAndFinalResponses() = runTest {
        NetworkEventLog.clear()
        val client = HttpClient(MockEngine) {
            followRedirects = true
            installNetworkEventLogging()
            engine {
                addHandler { request ->
                    if (request.url.encodedPath == "/start") {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "/final"),
                        )
                    } else {
                        respond(content = "ok", status = HttpStatusCode.OK)
                    }
                }
            }
        }

        val body = client.use { it.get("https://github.com/start").bodyAsText() }
        assertEquals("ok", body)
        val exchanges = NetworkEventLog.events.value.filter { it.source == "HTTP" }
        assertEquals(listOf(302, 200), exchanges.map { it.httpStatus })
        assertEquals("/final", exchanges.first().redirectLocation)
    }

}
