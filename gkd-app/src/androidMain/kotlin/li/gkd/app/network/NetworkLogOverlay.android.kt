package li.gkd.app.network

import kotlinx.coroutines.flow.StateFlow
import li.gkd.app.service.NetworkEventLogService

actual object NetworkLogOverlay {
    actual val isRunning: StateFlow<Boolean>
        get() = NetworkEventLogService.isRunning

    actual fun start() = NetworkEventLogService.start()
    actual fun stop() = NetworkEventLogService.stop()
}
