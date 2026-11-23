package li.gkd.app.ui.log

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.network.NetworkEvent
import li.gkd.app.network.NetworkEventLog
import li.gkd.app.time.format
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkTopAppBar

@Composable
fun NetworkEventLogPage() {
    val mainVm = MainViewModel.requireCurrent()
    val events by NetworkEventLog.events.collectAsStateWithLifecycle()
    GkScaffold(
        topBar = {
            GkTopAppBar(
                navigationIcon = {
                    GkIconButton(imageVector = GkIcons.ArrowBack, onClick = mainVm.navigator::pop)
                },
                title = { Text("网络日志") },
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
                TextButton(onClick = NetworkEventLog::clear, enabled = events.isNotEmpty()) {
                    Text("清空")
                }
            }
            if (events.isEmpty()) {
                Text(
                    "暂无网络日志。开启网络兼容性并触发 GitHub 请求后，这里会显示规则匹配、DNS 回退及连接方式。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events.asReversed(), key = { it.time.toString() + it.host + it.message }) { event ->
                        NetworkEventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkEventRow(event: NetworkEvent) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "${event.time.format("yyyy-MM-dd HH:mm:ss.SSS")}  ${event.level}",
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
