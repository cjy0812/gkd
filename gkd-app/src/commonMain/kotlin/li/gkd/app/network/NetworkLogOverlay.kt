package li.gkd.app.network

import kotlinx.coroutines.flow.StateFlow

expect object NetworkLogOverlay {
    val isRunning: StateFlow<Boolean>
    fun start()
    fun stop()
}
