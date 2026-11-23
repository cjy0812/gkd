package li.gkd.app.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.network.NetworkEvent
import li.gkd.app.network.NetworkEventLog
import li.gkd.app.network.toDiagnosticText
import li.gkd.app.resources.*
import li.gkd.app.time.format
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.util.copyText
import org.jetbrains.compose.resources.stringResource

@Composable
fun NetworkEventLogPage() {
    val mainVm = MainViewModel.requireCurrent()
    val events by NetworkEventLog.events.collectAsStateWithLifecycle()
    var selectedEvent by remember { mutableStateOf<NetworkEvent?>(null) }
    selectedEvent?.let { event ->
        li.gkd.app.ui.component.GkAlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = { Text("\${event.source} · \${event.level} · \${event.host}") },
            text = {
                Column {
                    Text(event.time.format("yyyy-MM-dd HH:mm:ss.SSS"))
                    if (event.method.isNotEmpty()) Text("\${event.method} \${event.requestUrl}")
                    event.statusCode?.let { Text("HTTP 状态：$it") }
                    event.redirectTarget?.let { Text("重定向目标：$it") }
                    Text(event.detail)
                    Text("事件 ID：\${event.id}")
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { copyText(event.toDiagnosticText()) }) {
                        Text(stringResource(Res.string.action_copy))
                    }
                    TextButton(onClick = { selectedEvent = null }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
            },
        )
    }
    GkScaffold(
        topBar = {
            GkTopAppBar(
                navigationIcon = {
                    GkIconButton(imageVector = GkIcons.ArrowBack, onClick = mainVm.navigator::pop)
                },
                title = { Text(stringResource(Res.string.network_event_logs_title)) },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { copyText(NetworkEventLog.export()) }, enabled = events.isNotEmpty()) {
                    Text(stringResource(Res.string.action_copy))
                }
                TextButton(onClick = NetworkEventLog::clear, enabled = events.isNotEmpty()) {
                    Text(stringResource(Res.string.network_event_logs_clear))
                }
            }
            if (events.isEmpty()) {
                Text(
                    stringResource(Res.string.network_event_logs_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events.asReversed(), key = { it.id }) { event ->
                        NetworkEventRow(event, onClick = { selectedEvent = event })
                    }
                    item { GkPageBottomSpace() }
                }
            }
        }
    }
}

@Composable
private fun NetworkEventRow(event: NetworkEvent, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "\${event.time.format("yyyy-MM-dd HH:mm:ss.SSS")}  \${event.source} · \${event.level}",
            style = MaterialTheme.typography.labelSmall,
            color = when (event.level) {
                "ERROR" -> MaterialTheme.colorScheme.error
                "WARN" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(event.host, style = MaterialTheme.typography.titleSmall)
        Text(event.message, style = MaterialTheme.typography.bodySmall)
    }
}
