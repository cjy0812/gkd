package li.gkd.app.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import io.ktor.util.getOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkEvent(
    val time: Long,
    val host: String,
    val level: String,
    val message: String,
    val id: Long = time,
    val detail: String = message,
    val requestUrl: String = "",
    val method: String = "",
    val source: String = "NETWORK",
    val requestId: Long? = null,
    val redirectChainId: Long? = null,
    val hop: Int? = null,
    val phase: String? = null,
    val httpStatus: Int? = null,
    val redirectLocation: String? = null,
    val durationMs: Long? = null,
    val errorType: String? = null,
    val errorChain: String? = null,
    val dnsServer: String? = null,
    val resolvedIps: List<String> = emptyList(),
    val selectedIp: String? = null,
    val sni: String? = null,
    val tlsProtocol: String? = null,
    val peerSubject: String? = null,
    val certificateSha256: String? = null,
)

object NetworkEventLog {
    private const val MAX_EVENTS = 1000
    private const val MAX_IN_FLIGHT = 1000
    private const val REQUEST_STATE_TTL_MILLIS = 120_000L

    private data class RequestState(val hop: Int, val lastEventAt: Long)

    private val lock = Any()
    private val nextId = AtomicLong(System.currentTimeMillis() * 1000)
    private val nextRequestId = AtomicLong(System.currentTimeMillis() * 1000)
    private val requestStates = mutableMapOf<Long, RequestState>()
    private val mutableEvents = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    fun newRequestId(): Long = nextRequestId.incrementAndGet()

    fun info(
        host: String,
        message: String,
        source: String = "NETWORK",
        phase: String? = null,
        requestId: Long? = null,
        dnsServer: String? = null,
        resolvedIps: List<String> = emptyList(),
        selectedIp: String? = null,
        sni: String? = null,
        durationMs: Long? = null,
        tlsProtocol: String? = null,
        peerSubject: String? = null,
        certificateSha256: String? = null,
    ) = append(
        level = "INFO",
        source = source,
        host = host,
        message = message,
        phase = phase,
        requestId = requestId,
        dnsServer = dnsServer,
        resolvedIps = resolvedIps,
        selectedIp = selectedIp,
        sni = sni,
        durationMs = durationMs,
        tlsProtocol = tlsProtocol,
        peerSubject = peerSubject,
        certificateSha256 = certificateSha256,
    )

    fun warn(
        host: String,
        message: String,
        source: String = "NETWORK",
        phase: String? = null,
        requestId: Long? = null,
    ) = append(
        level = "WARN",
        source = source,
        host = host,
        message = message,
        phase = phase,
        requestId = requestId,
    )

    fun error(
        host: String,
        message: String,
        source: String = "NETWORK",
        phase: String? = null,
        requestId: Long? = null,
        errorType: String? = null,
        errorChain: String? = null,
        durationMs: Long? = null,
    ) = append(
        level = "ERROR",
        source = source,
        host = host,
        message = message,
        phase = phase,
        requestId = requestId,
        errorType = errorType,
        errorChain = errorChain,
        durationMs = durationMs,
    )

    fun clear() {
        synchronized(lock) {
            mutableEvents.value = emptyList()
            requestStates.clear()
        }
    }

    fun request(
        host: String,
        method: String,
        url: String,
        status: Int? = null,
        error: String? = null,
        source: String = "HTTP",
        requestId: Long? = null,
        hop: Int? = null,
        redirectLocation: String? = null,
        durationMs: Long? = null,
        phase: String? = null,
        errorType: String? = null,
        errorChain: String? = null,
        dnsServer: String? = null,
        resolvedIps: List<String> = emptyList(),
        selectedIp: String? = null,
        sni: String? = null,
    ) {
        val safeUrl = sanitizeUrl(url)
        val safeLocation = redirectLocation?.let(::sanitizeUrl)
        val level = when {
            error != null -> "ERROR"
            status != null && status >= 400 -> "ERROR"
            status != null && status in 300..399 -> "WARN"
            else -> "INFO"
        }
        val summary = buildString {
            append(method)
            append(" ")
            append(safeUrl)
            status?.let { append(" · HTTP "); append(it) }
            safeLocation?.let { append(" · Location: "); append(it) }
            error?.let { append(" · "); append(sanitizeText(it)) }
        }
        append(
            level = level,
            source = source,
            host = host,
            message = summary,
            detail = summary,
            requestUrl = safeUrl,
            method = method,
            requestId = requestId,
            redirectChainId = requestId,
            hop = hop,
            phase = phase ?: if (error != null) "REQUEST_FAILED" else "HTTP_RESPONSE",
            httpStatus = status,
            redirectLocation = safeLocation,
            durationMs = durationMs,
            errorType = errorType,
            errorChain = errorChain,
            dnsServer = dnsServer,
            resolvedIps = resolvedIps,
            selectedIp = selectedIp,
            sni = sni,
        )
    }

    fun requestStarted(host: String, method: String, url: String, requestId: Long, source: String = "HTTP") {
        val safeUrl = sanitizeUrl(url)
        append(
            level = "INFO",
            source = source,
            host = host,
            message = "请求开始：$method $safeUrl",
            detail = "$source 请求开始\nmethod=$method\nurl=$safeUrl",
            requestUrl = safeUrl,
            method = method,
            requestId = requestId,
            redirectChainId = requestId,
            phase = "REQUEST_START",
        )
    }

    fun response(
        host: String,
        method: String,
        url: String,
        status: Int,
        redirectLocation: String?,
        requestId: Long?,
        startedAt: Long?,
        source: String = "HTTP",
    ) {
        val now = System.currentTimeMillis()
        val isRedirect = status in 300..399 && !redirectLocation.isNullOrBlank()
        val (hop, duration) = requestId?.let { id ->
            synchronized(lock) {
                pruneRequestStates(now)
                val previous = requestStates[id]
                val nextHop = (previous?.hop ?: 0) + 1
                val elapsed = (now - (previous?.lastEventAt ?: startedAt ?: now)).coerceAtLeast(0)
                if (isRedirect) {
                    requestStates[id] = RequestState(nextHop, now)
                    while (requestStates.size > MAX_IN_FLIGHT) {
                        requestStates.remove(requestStates.keys.firstOrNull())
                    }
                } else {
                    requestStates.remove(id)
                }
                nextHop to elapsed
            }
        } ?: (null to startedAt?.let { (now - it).coerceAtLeast(0) })

        val event = buildEvent(
            host = host,
            method = method,
            url = url,
            status = status,
            redirectLocation = redirectLocation,
            requestId = requestId,
            durationMs = duration,
            hop = hop,
            phase = if (isRedirect) "REDIRECT_RESPONSE" else "HTTP_RESPONSE",
            source = source,
        )
        appendEvent(event)
    }

    fun requestFailed(
        host: String,
        method: String,
        url: String,
        error: Throwable,
        requestId: Long?,
        startedAt: Long?,
        source: String = "HTTP",
    ) {
        val now = System.currentTimeMillis()
        val state = requestId?.let { id ->
            synchronized(lock) {
                pruneRequestStates(now)
                requestStates.remove(id)
            }
        }
        val duration = state?.let { (now - it.lastEventAt).coerceAtLeast(0) }
            ?: startedAt?.let { (now - it).coerceAtLeast(0) }
        val chain = throwableChain(error)
        val safeMessage = error.message?.takeIf(String::isNotBlank)?.let(::sanitizeText)
        request(
            host = host,
            method = method,
            url = url,
            error = "${error::class.simpleName ?: "Exception"}${safeMessage?.let { ": $it" }.orEmpty()}",
            source = source,
            requestId = requestId,
            durationMs = duration,
            phase = "REQUEST_FAILED",
            errorType = error::class.simpleName,
            errorChain = chain,
            hop = state?.hop?.plus(1),
        )
    }

    private fun buildEvent(
        host: String,
        method: String,
        url: String,
        status: Int,
        redirectLocation: String?,
        requestId: Long?,
        durationMs: Long?,
        hop: Int?,
        phase: String,
        source: String,
    ): NetworkEvent {
        val safeUrl = sanitizeUrl(url)
        val safeLocation = redirectLocation?.let(::sanitizeUrl)
        val level = when {
            status >= 400 -> "ERROR"
            status in 300..399 -> "WARN"
            else -> "INFO"
        }
        val message = buildString {
            append(method)
            append(" ")
            append(safeUrl)
            append(" · HTTP ")
            append(status)
            safeLocation?.let { append(" · Location: "); append(it) }
        }
        return NetworkEvent(
            time = System.currentTimeMillis(),
            host = host,
            level = level,
            message = message,
            id = nextId.incrementAndGet(),
            detail = message,
            requestUrl = safeUrl,
            method = method,
            source = source,
            requestId = requestId,
            redirectChainId = requestId,
            hop = hop,
            phase = phase,
            httpStatus = status,
            redirectLocation = safeLocation,
            durationMs = durationMs,
        )
    }

    private fun appendEvent(event: NetworkEvent) {
        synchronized(lock) {
            mutableEvents.value = (mutableEvents.value + event).takeLast(MAX_EVENTS)
        }
    }

    private fun pruneRequestStates(now: Long) {
        requestStates.entries.removeAll { now - it.value.lastEventAt > REQUEST_STATE_TTL_MILLIS }
    }

    private fun append(
        level: String,
        source: String,
        host: String,
        message: String,
        detail: String = message,
        requestUrl: String = "",
        method: String = "",
        requestId: Long? = null,
        redirectChainId: Long? = requestId,
        hop: Int? = null,
        phase: String? = null,
        httpStatus: Int? = null,
        redirectLocation: String? = null,
        durationMs: Long? = null,
        errorType: String? = null,
        errorChain: String? = null,
        dnsServer: String? = null,
        resolvedIps: List<String> = emptyList(),
        selectedIp: String? = null,
        sni: String? = null,
        tlsProtocol: String? = null,
        peerSubject: String? = null,
        certificateSha256: String? = null,
    ) {
        val event = NetworkEvent(
            time = System.currentTimeMillis(),
            host = host,
            level = level,
            message = sanitizeText(message),
            id = nextId.incrementAndGet(),
            detail = sanitizeText(detail),
            requestUrl = requestUrl.takeIf(String::isNotBlank)?.let(::sanitizeUrl).orEmpty(),
            method = method,
            source = source,
            requestId = requestId,
            redirectChainId = redirectChainId,
            hop = hop,
            phase = phase,
            httpStatus = httpStatus,
            redirectLocation = redirectLocation?.let(::sanitizeUrl),
            durationMs = durationMs,
            errorType = errorType,
            errorChain = errorChain?.let(::sanitizeText),
            dnsServer = dnsServer,
            resolvedIps = resolvedIps,
            selectedIp = selectedIp,
            sni = sni,
            tlsProtocol = tlsProtocol,
            peerSubject = peerSubject,
            certificateSha256 = certificateSha256,
        )
        appendEvent(event)
    }
}

private val NetworkRequestIdKey = AttributeKey<Long>("GkdNetworkRequestId")
private val NetworkRequestStartedAtKey = AttributeKey<Long>("GkdNetworkRequestStartedAt")

private val NetworkRequestTracker = createClientPlugin("GkdNetworkRequestTracker") {
    onRequest { request, _ ->
        val requestId = NetworkEventLog.newRequestId()
        val startedAt = System.currentTimeMillis()
        request.attributes.put(NetworkRequestIdKey, requestId)
        request.attributes.put(NetworkRequestStartedAtKey, startedAt)
        request.headers.append(GKD_NETWORK_REQUEST_ID_HEADER, requestId.toString())
        request.headers.append(GKD_NETWORK_REQUEST_STARTED_HEADER, startedAt.toString())
        request.headers.append(GKD_NETWORK_REQUEST_SOURCE_HEADER, "HTTP")
        request.headers.append(GKD_NETWORK_REQUEST_HOST_HEADER, request.url.host)
        NetworkEventLog.requestStarted(
            host = request.url.host,
            method = request.method.value,
            url = request.url.build().toString(),
            requestId = requestId,
        )
    }
}

fun HttpClientConfig<*>.installNetworkEventLogging() {
    install(NetworkRequestTracker)
    HttpResponseValidator {
        validateResponse { response ->
            val request = response.call.request
            NetworkEventLog.response(
                host = request.url.host,
                method = request.method.value,
                url = request.url.toString(),
                status = response.status.value,
                redirectLocation = response.headers[HttpHeaders.Location],
                requestId = request.attributes.getOrNull(NetworkRequestIdKey),
                startedAt = request.attributes.getOrNull(NetworkRequestStartedAtKey),
            )
        }
        handleResponseExceptionWithRequest { cause, request ->
            NetworkEventLog.requestFailed(
                host = request.url.host,
                method = request.method.value,
                url = request.url.toString(),
                error = cause,
                requestId = request.attributes.getOrNull(NetworkRequestIdKey),
                startedAt = request.attributes.getOrNull(NetworkRequestStartedAtKey),
            )
        }
    }
}

const val GKD_NETWORK_REQUEST_ID_HEADER = "X-GKD-Diagnostic-Request-Id"
const val GKD_NETWORK_REQUEST_STARTED_HEADER = "X-GKD-Diagnostic-Started-At"
const val GKD_NETWORK_REQUEST_SOURCE_HEADER = "X-GKD-Diagnostic-Source"
const val GKD_NETWORK_REQUEST_HOST_HEADER = "X-GKD-Diagnostic-Original-Host"

fun NetworkEvent.toDiagnosticText(): String = buildString {
    appendLine("time_ms=$time")
    appendLine("event_id=$id")
    appendLine("source=$source")
    appendLine("level=$level")
    appendLine("host=$host")
    requestId?.let { appendLine("request_id=$it") }
    redirectChainId?.let { appendLine("redirect_chain_id=$it") }
    phase?.let { appendLine("phase=$it") }
    hop?.let { appendLine("hop=$it") }
    if (method.isNotBlank()) appendLine("method=$method")
    if (requestUrl.isNotBlank()) appendLine("url=$requestUrl")
    httpStatus?.let { appendLine("http_status=$it") }
    redirectLocation?.let { appendLine("redirect_location=$it") }
    durationMs?.let { appendLine("duration_ms=$it") }
    dnsServer?.let { appendLine("dns_server=$it") }
    if (resolvedIps.isNotEmpty()) appendLine("resolved_ips=${resolvedIps.joinToString()}")
    selectedIp?.let { appendLine("selected_ip=$it") }
    sni?.let { appendLine("sni=${it.ifEmpty { "(empty)" }}") }
    tlsProtocol?.let { appendLine("tls_protocol=$it") }
    peerSubject?.let { appendLine("peer_subject=$it") }
    certificateSha256?.let { appendLine("certificate_sha256=$it") }
    errorType?.let { appendLine("error_type=$it") }
    errorChain?.let { appendLine("error_chain=$it") }
    append("detail=${sanitizeText(detail)}")
}

private fun sanitizeUrl(url: String): String =
    url.substringBefore("?").substringBefore("#")

private val diagnosticUrlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

private fun sanitizeText(value: String): String =
    diagnosticUrlPattern.replace(value) { match ->
        val raw = match.value.trimEnd(',', '.', ')', ']', '}')
        sanitizeUrl(raw) + match.value.drop(raw.length)
    }.take(8_000)

private fun throwableChain(error: Throwable): String {
    val parts = mutableListOf<String>()
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current) && parts.size < 8) {
        val type = current::class.simpleName ?: "Throwable"
        val message = current.message?.takeIf(String::isNotBlank)?.let(::sanitizeText)
        parts += if (message == null) type else "$type: $message"
        current = current.cause
    }
    val frames = error.stackTrace.take(6).joinToString("\n") { "  at $it" }
    return (parts + frames.takeIf(String::isNotBlank).orEmpty()).filter(String::isNotBlank).joinToString("\n")
}
