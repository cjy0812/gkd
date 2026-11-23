package li.gkd.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import li.gkd.app.network.GithubCertificateReport
import li.gkd.app.network.GithubHostRules
import li.gkd.app.network.probeGithubCertificate
import li.gkd.app.network.NetworkClients
import li.gkd.app.resources.Res
import li.gkd.app.resources.action_cancel
import li.gkd.app.resources.action_save
import li.gkd.app.resources.github_network_rules_json
import li.gkd.app.resources.github_network_rules_url
import li.gkd.app.resources.github_network_import_from_url
import li.gkd.app.resources.github_network_importing
import li.gkd.app.resources.github_network_capture_baseline
import li.gkd.app.resources.github_network_verify_rule
import li.gkd.app.resources.github_network_settings_title
import li.gkd.app.ui.component.GkAlertDialog
import org.jetbrains.compose.resources.stringResource

@Composable
fun GithubNetworkSettingsDialog(
    initialUrl: String,
    initialRules: String,
    initialBaselineFingerprint: String,
    initialBaselineJson: String,
    onDismiss: () -> Unit,
    onSave: (url: String, rulesJson: String, baselineFingerprint: String, baselineJson: String) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var rulesText by remember(initialRules) {
        mutableStateOf(initialRules.ifBlank { "[]" })
    }
    var baselineFingerprint by remember(initialBaselineFingerprint) {
        mutableStateOf(initialBaselineFingerprint)
    }
    var baselineJson by remember(initialBaselineJson) { mutableStateOf(initialBaselineJson) }
    var status by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    GkAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.github_network_settings_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = url,
                    onValueChange = { url = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.github_network_rules_url)) },
                    singleLine = true,
                )
                TextField(
                    value = rulesText,
                    onValueChange = { rulesText = it; status = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text(stringResource(Res.string.github_network_rules_json)) },
                    minLines = 7,
                    maxLines = 14,
                )
                status?.let { Text(text = it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !importing,
                onClick = {
                    try {
                        val parsed = GithubHostRules.parse(rulesText)
                        onSave(url.trim(), GithubHostRules.encode(parsed), baselineFingerprint, baselineJson)
                    } catch (error: Exception) {
                        status = error.message ?: "规则格式无效"
                    }
                },
            ) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !importing,
                onClick = onDismiss,
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
            TextButton(
                enabled = !importing,
                onClick = {
                    scope.launch {
                        importing = true
                        status = null
                        try {
                            require(url.startsWith("https://", ignoreCase = true)) {
                                "规则 URL 必须使用 HTTPS"
                            }
                            val source = NetworkClients.client.get(url.trim()).bodyAsText()
                            val parsed = GithubHostRules.parse(source)
                            rulesText = GithubHostRules.encode(parsed)
                            status = "导入成功：已提取 ${parsed.size} 条 GitHub 规则，可继续手动编辑"
                        } catch (error: Exception) {
                            status = "导入失败：${error.message ?: error::class.simpleName}"
                        } finally {
                            importing = false
                        }
                    }
                },
            ) {
                Text(stringResource(if (importing) Res.string.github_network_importing else Res.string.github_network_import_from_url))
            }
            TextButton(
                enabled = !importing,
                onClick = {
                    scope.launch {
                        importing = true
                        status = null
                        try {
                            val report = probeGithubCertificate("api.github.com", null, null)
                            baselineFingerprint = report.sha256
                            baselineJson = Json.encodeToString(report)
                            status = "已记录基准证书：${report.sha256}\n签发者：${report.issuer}\nSAN：${report.subjectAltNames.joinToString()}"
                        } catch (error: Exception) {
                            status = "基准连接失败：${error.message ?: error::class.simpleName}"
                        } finally {
                            importing = false
                        }
                    }
                },
            ) {
                Text(stringResource(Res.string.github_network_capture_baseline))
            }
            TextButton(
                enabled = !importing,
                onClick = {
                    scope.launch {
                        importing = true
                        status = null
                        try {
                            val rule = GithubHostRules.find(GithubHostRules.parse(rulesText), "api.github.com")
                                ?: error("当前规则中没有 api.github.com")
                            val report = probeGithubCertificate("api.github.com", rule.ip, rule.sni)
                            val comparison = when {
                                baselineFingerprint.isBlank() -> "尚未采集基准；本次握手和域名验证成功"
                                baselineFingerprint == report.sha256 -> "证书指纹与基准相同"
                                else -> "证书指纹与基准不同（可能是证书轮换或 CDN 差异），不据此绕过验证"
                            }
                            status = "${comparison}\nSHA-256：${report.sha256}\n签发者：${report.issuer}\nSAN：${report.subjectAltNames.joinToString()}\n有效期：${report.notBefore} 至 ${report.notAfter}"
                        } catch (error: Exception) {
                            status = "规则验证失败：${error.message ?: error::class.simpleName}"
                        } finally {
                            importing = false
                        }
                    }
                },
            ) {
                Text(stringResource(Res.string.github_network_verify_rule))
            }
        },
    )
}
