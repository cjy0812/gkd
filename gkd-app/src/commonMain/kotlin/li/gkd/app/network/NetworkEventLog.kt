package li.gkd.app.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.http.HttpHeaders
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
    val statusCode: Int? = null,
    val redirectTarget: String? = null,
)

object NetworkEventLog {
    private const val MAX_EVENTS = 300
    private val lock = Any()
    private val nextId = AtomicLong(System.currentTimeMillis() * 1000)
    private val mutableEvents = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    fun info(host: String, message: String) = append("INFO", host, message)
    fun warn(host: String, message: String) = append("WARN", host, message)
    fun error(host: String, message: String) = append("ERROR", host, message)

    fun clear() {
        synchronized(lock) { mutableEvents.value = emptyList() }
    }

    /**
     * Records one actual HTTP exchange. A redirect response is an exchange too, not the end
     * of the request: its Location is retained so the next hop can be diagnosed independently.
     */
    fun request(
        host: String,
        method: String,
        url: String,
        status: Int? = null,
        error: String? = null,
        redirectLocation: String? = null,
        source: String = "HTTP",
    ) {
        val safeUrl = sanitizeUrl(url)
        val safeRedirectTarget = redirectLocation?.let(::sanitizeUrl)?.takeIf(String::isNotBlank)
        val level = when {
            error != null -> "ERROR"
            status != null && status >= 400 -> "ERROR"
            status != null && status in 300..399 -> "WARN"
            else -> "INFO"
        }
        val summary = buildString {
            append("[$source] ")
            append(method)
            append(' ')
            append(safeUrl)
            status?.let { append(" · HTTP "); append(it) }
            safeRedirectTarget?.let { append(" · Location: "); append(it) }
            error?.let { append(" · "); append(sanitizeDiagnosticText(it)) }
        }
        append(
            level = level,
            host = host,
            message = summary,
            detail = summary,
            requestUrl = safeUrl,
            method = method,
            source = source,
            statusCode = status,
            redirectTarget = safeRedirectTarget,
        )
    }

    fun export(): String =
        events.value.joinToString(separator = "\n\n") { it.toDiagnosticText() }

    private fun append(
        level: String,
        host: String,
        message: String,
        detail: String = message,
        requestUrl: String = "",
        method: String = "",
        source: String = "NETWORK",
        statusCode: Int? = null,
        redirectTarget: String? = null,
    ) {
        val time = System.currentTimeMillis()
        val event = NetworkEvent(
            time = time,
            host = host,
            level = level,
            message = message,
            id = nextId.incrementAndGet(),
            detail = detail,
            requestUrl = requestUrl,
            method = method,
            source = source,
            statusCode = statusCode,
            redirectTarget = redirectTarget,
        )
        synchronized(lock) {
            mutableEvents.value = (mutableEvents.value + event).takeLast(MAX_EVENTS)
        }
    }
}

fun NetworkEvent.toDiagnosticText(): String =
    buildString {
        appendLine("time_ms=$time")
        appendLine("event_id=$id")
        appendLine("source=$source")
        appendLine("level=$level")
        appendLine("host=$host")
        if (method.isNotEmpty()) appendLine("method=$method")
        if (requestUrl.isNotEmpty()) appendLine("url=$requestUrl")
        statusCode?.let { appendLine("http_status=$it") }
        redirectTarget?.let { appendLine("redirect_location=$it") }
        append("detail=$detail")
    }

private fun sanitizeUrl(value: String): String {
    val withoutQuery = value.substringBefore('?').substringBefore('#')
    val separator = withoutQuery.indexOf("://")
    if (separator < 0) return withoutQuery
    val scheme = withoutQuery.substring(0, separator)
    val authorityAndPath = withoutQuery.substring(separator + 3)
    val authority = authorityAndPath.substringBefore('/')
    val safeAuthority = authority.substringAfterLast('@')
    val path = authorityAndPath.removePrefix(authority)
    return "$scheme://$safeAuthority$path"
}

private fun sanitizeDiagnosticText(value: String): String =
    value
        .replace(
            Regex("(?i)([?&](?:access_token|token|key|password|signature|sig)=)[^&#\\s]+"),
            "$1[redacted]",
        )
        .replace(
            Regex("(?i)(authorization|cookie|set-cookie):\\s*[^\\r\\n]+"),
            "$1: [redacted]",
        )

private fun describeNetworkFailure(cause: Throwable): String =
    generateSequence(cause) { it.cause }
        .take(6)
        .joinToString(" <- ") { error ->
            val type = error::class.simpleName ?: "Throwable"
            val description = networkFailureSummary(error)
                ?: error.message?.lineSequence()?.firstOrNull()
            if (description.isNullOrBlank()) type else "$type: $description"
        }

fun HttpClientConfig<*>.installNetworkEventLogging(source: String = "HTTP") {
    HttpResponseValidator {
        validateResponse { response ->
            NetworkEventLog.request(
                host = response.call.request.url.host,
                method = response.call.request.method.value,
                url = response.call.request.url.toString(),
                status = response.status.value,
                redirectLocation = response.headers[HttpHeaders.Location],
                source = source,
            )
        }
        handleResponseExceptionWithRequest { cause, request ->
            NetworkEventLog.request(
                host = request.url.host,
                method = request.method.value,
                url = request.url.toString(),
                error = describeNetworkFailure(cause),
                source = source,
            )
        }
    }
}
