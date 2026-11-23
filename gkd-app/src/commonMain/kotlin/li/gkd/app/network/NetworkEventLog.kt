package li.gkd.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import li.gkd.app.logging.LogUtils

data class NetworkEvent(
    val time: Long,
    val host: String,
    val level: String,
    val message: String,
)

object NetworkEventLog {
    private const val MAX_EVENTS = 300
    private val lock = Any()
    private val mutableEvents = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    fun info(host: String, message: String) = append("INFO", host, message)
    fun warn(host: String, message: String) = append("WARN", host, message)
    fun error(host: String, message: String) = append("ERROR", host, message)

    fun clear() {
        synchronized(lock) { mutableEvents.value = emptyList() }
        LogUtils.i("network compatibility logs cleared", tag = "Network")
    }

    private fun append(level: String, host: String, message: String) {
        val event = NetworkEvent(System.currentTimeMillis(), host, level, message)
        synchronized(lock) {
            mutableEvents.value = (mutableEvents.value + event).takeLast(MAX_EVENTS)
        }
        LogUtils.i("[$level] $host: $message", tag = "Network")
    }
}
