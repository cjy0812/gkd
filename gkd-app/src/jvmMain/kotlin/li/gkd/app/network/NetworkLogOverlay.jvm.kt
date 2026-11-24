package li.gkd.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object NetworkLogOverlay {
    actual val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun start() = Unit
    actual fun stop() = Unit
}
