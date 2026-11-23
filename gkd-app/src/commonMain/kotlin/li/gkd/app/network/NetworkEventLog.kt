package li.gkd.app.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import li.gkd.app.logging.LogUtils

data class NetworkEvent(
    val time: Long,
    val host: String,
    val level: String,
    val message: String,
    val id: Long = time,
    val detail: String = message,
    val requestUrl: String = "",
    val method: String = "",
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
        LogUtils.i("network compatibility logs cleared", tag = "Network")
    }

    fun request(host: String, method: String, url: String, status: Int? = null, error: String? = null) {
        val safeUrl = url.substringBefore("?").substringBefore("#")
        val summary = buildString {
            append(method)
            append(" ")
            append(safeUrl)
            status?.let { append(" · HTTP "); append(it) }
            error?.let { append(" · "); append(it) }
        }
        append(if (error == null) "INFO" else "ERROR", host, summary, summary, safeUrl, method)
    }

    private fun append(level: String, host: String, message: String, detail: String = message, requestUrl: String = "", method: String = "") {
        val time = System.currentTimeMillis()
        val event = NetworkEvent(time, host, level, message, nextId.incrementAndGet(), detail, requestUrl, method)
        synchronized(lock) {
            mutableEvents.value = (mutableEvents.value + event).takeLast(MAX_EVENTS)
        }
        LogUtils.i("[$level] $host: $message", tag = "Network")
    }
}

fun HttpClientConfig<*>.installNetworkEventLogging() {
    HttpResponseValidator {
        validateResponse { response ->
            NetworkEventLog.request(
                response.call.request.url.host,
                response.call.request.method.value,
                response.call.request.url.toString(),
                status = response.status.value,
            )
        }
        handleResponseExceptionWithRequest { cause, request ->
            NetworkEventLog.request(
                request.url.host,
                request.method.value,
                request.url.toString(),
                error = "${cause::class.simpleName}: ${networkFailureSummary(cause) ?: "request failed"}",
            )
        }
    }
}
