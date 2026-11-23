package li.gkd.app.service

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import li.gkd.app.network.NetworkEvent
import li.gkd.app.network.NetworkEventLog
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.permission.PermissionStates
import li.gkd.app.resources.*
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.icon.DragPan
import li.gkd.app.util.IntentUtils
import org.jetbrains.compose.resources.stringResource

class NetworkEventLogService : OverlayWindowService(positionKey = "network-log") {
    private var minimized by mutableStateOf(false)

    override fun isViewClickEnabled(): Boolean = minimized

    override fun onClickView() {
        minimized = false
    }

    override fun onLongClickView() {
        if (!minimized) minimized = true
    }

    @Composable
    override fun ComposeContent() {
        val events by NetworkEventLog.events.collectAsStateWithLifecycle()
        val bg = MaterialTheme.colorScheme.surface
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (minimized) {
                GkIcon(
                    imageVector = GkIcons.ExpandContent,
                    contentDescription = stringResource(Res.string.event_log_window_restore),
                    modifier = Modifier.clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(10.dp),
                )
            } else {
                Column(
                    modifier = Modifier.clip(MaterialTheme.shapes.small)
                        .background(bg.copy(alpha = 0.94f))
                        .padding(6.dp)
                        .heightIn(max = 320.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)) {
                        GkIcon(imageVector = DragPan)
                        Text(stringResource(Res.string.network_log_overlay), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(Res.string.network_log_overlay_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn {
                        items(events.asReversed().take(12), key = { it.id }) { event ->
                            NetworkLogOverlayRow(event)
                        }
                    }
                }
            }
        }
    }

    init {
        useLogLifecycle()
        useServicePresence(
            stateFlow = isRunning,
            name = Res.string.network_log_overlay,
        )
        onCreated {
            NotificationCatalog.networkLog().startForeground()
        }
    }

    companion object {
        val isRunning = MutableStateFlow(false)

        fun start() {
            if (!PermissionStates.drawOverlays.checkOrToast()) return
            IntentUtils.startForegroundService(NetworkEventLogService::class)
        }

        fun stop() = IntentUtils.stopService(NetworkEventLogService::class)
    }
}

@Composable
private fun NetworkLogOverlayRow(event: NetworkEvent) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
    ) {
        androidx.compose.foundation.layout.Row {
            Text(
                text = event.level,
                color = when (event.level) {
                    "ERROR" -> MaterialTheme.colorScheme.error
                    "WARN" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "  ${event.host}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = event.message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}
