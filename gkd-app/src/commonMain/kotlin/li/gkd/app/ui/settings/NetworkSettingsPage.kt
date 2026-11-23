package li.gkd.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.network.NetworkLogOverlay
import li.gkd.app.resources.*
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkSettingsDialog
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.GkOutlinedTextField
import li.gkd.app.ui.navigation.NetworkEventLogRoute
import org.jetbrains.compose.resources.stringResource

@Composable
fun NetworkSettingsPage() {
    val mainVm = MainViewModel.requireCurrent()
    val store by SettingsRepository.settings.collectAsStateWithLifecycle()
    val overlayRunning by NetworkLogOverlay.isRunning.collectAsStateWithLifecycle()
    var showRules by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }
    var showCustomDns by remember { mutableStateOf(false) }
    var showCustomDnsServer by remember { mutableStateOf(false) }
    var customDns by remember(store.githubDnsCustomUrl) { mutableStateOf(store.githubDnsCustomUrl) }
    var customDnsServer by remember(store.githubDnsCustomServer) { mutableStateOf(store.githubDnsCustomServer) }

    if (showRules) {
        GithubNetworkSettingsDialog(
            initialUrl = store.githubHostRulesUrl,
            initialRules = store.githubHostRulesJson,
            initialBaselineFingerprint = store.githubTlsBaselineFingerprint,
            initialBaselineJson = store.githubTlsBaselineJson,
            onDismiss = { showRules = false },
            onSave = { url, rulesJson, fingerprint, baseline ->
                SettingsRepository.updateSettings {
                    it.copy(
                        githubHostRulesUrl = url,
                        githubHostRulesJson = rulesJson,
                        githubTlsBaselineFingerprint = fingerprint,
                        githubTlsBaselineJson = baseline,
                    )
                }
                showRules = false
            },
        )
    }

    if (showDns) {
        GkSettingsDialog(
            title = stringResource(Res.string.network_dns_title),
            onDismissRequest = { showDns = false },
        ) {
            listOf(
                "system" to Res.string.network_dns_system,
                "cloudflare" to Res.string.network_dns_cloudflare,
                "google" to Res.string.network_dns_google,
                "adguard" to Res.string.network_dns_adguard,
                "custom-dns" to Res.string.network_dns_custom_server,
                "custom-doh" to Res.string.network_dns_custom_doh,
            ).forEach { (mode, label) ->
                TextButton(
                    onClick = {
                        if (mode == "custom") {
                            showDns = false
                            showCustomDns = true
                        } else {
                            SettingsRepository.updateSettings { it.copy(githubDnsMode = mode) }
                            showDns = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(label))
                }
            }
        }
    }

    if (showCustomDns) {
        GkSettingsDialog(
            title = stringResource(Res.string.network_dns_custom),
            onDismissRequest = { showCustomDns = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    stringResource(Res.string.network_dns_custom_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GkOutlinedTextField(
                    value = customDns,
                    onValueChange = { customDns = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(onClick = { showCustomDns = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val value = customDns.trim()
                            if (value.startsWith("https://", ignoreCase = true) && value.contains("{name}")) {
                                SettingsRepository.updateSettings {
                                    it.copy(githubDnsMode = "custom-doh", githubDnsCustomUrl = value)
                                }
                                showCustomDns = false
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }

    GkScaffold(
        topBar = {
            GkTopAppBar(
                title = { Text(stringResource(Res.string.network_settings_title)) },
                navigationIcon = {
                    GkIconButton(imageVector = GkIcons.ArrowBack, onClick = mainVm.navigator::pop)
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(Res.string.network_compatibility_section),
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            GkTextSwitch(
                title = stringResource(Res.string.github_network_compatibility),
                subtitle = stringResource(Res.string.github_network_compatibility_description),
                checked = store.enableGithubNetworkCompatibility,
                onCheckedChange = { enabled ->
                    SettingsRepository.updateSettings { it.copy(enableGithubNetworkCompatibility = enabled) }
                },
            )
            GkSettingItem(
                title = stringResource(Res.string.github_network_settings_title),
                subtitle = stringResource(Res.string.network_rules_summary, runCatching { li.gkd.app.network.GithubHostRules.parse(store.githubHostRulesJson).size }.getOrDefault(0)),
                onClick = { showRules = true },
            )
            Text(
                stringResource(Res.string.network_sni_strategy),
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SniModeOption(
                selected = !store.githubUseOkHttpSniWorkaround && !store.githubEmptySniFallback,
                enabled = store.enableGithubNetworkCompatibility,
                title = stringResource(Res.string.network_sni_native),
                subtitle = stringResource(Res.string.network_sni_native_description),
                onClick = {
                    SettingsRepository.updateSettings {
                        it.copy(githubUseOkHttpSniWorkaround = false, githubEmptySniFallback = false)
                    }
                },
            )
            SniModeOption(
                selected = store.githubUseOkHttpSniWorkaround,
                enabled = store.enableGithubNetworkCompatibility,
                title = stringResource(Res.string.github_okhttp_sni_workaround),
                subtitle = stringResource(Res.string.github_okhttp_sni_workaround_description),
                onClick = {
                    SettingsRepository.updateSettings {
                        it.copy(githubUseOkHttpSniWorkaround = true, githubEmptySniFallback = false)
                    }
                },
            )
            SniModeOption(
                selected = !store.githubUseOkHttpSniWorkaround && store.githubEmptySniFallback,
                enabled = store.enableGithubNetworkCompatibility,
                title = stringResource(Res.string.network_sni_empty_fallback),
                subtitle = stringResource(Res.string.network_sni_empty_fallback_description),
                onClick = {
                    SettingsRepository.updateSettings {
                        it.copy(githubUseOkHttpSniWorkaround = false, githubEmptySniFallback = true)
                    }
                },
            )
            Text(
                stringResource(Res.string.network_dns_section),
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            GkSettingItem(
                title = stringResource(Res.string.network_dns_title),
                subtitle = stringResource(
                    when (store.githubDnsMode) {
                        "cloudflare" -> Res.string.network_dns_cloudflare
                        "google" -> Res.string.network_dns_google
                        "adguard" -> Res.string.network_dns_adguard
                        "custom", "custom-doh" -> Res.string.network_dns_custom_doh\n                        "custom-dns" -> Res.string.network_dns_custom_server
                        else -> Res.string.network_dns_system
                    },
                ),
                onClick = { showDns = true },
            )
            Text(
                stringResource(Res.string.network_diagnostics_section),
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            GkSettingItem(
                title = stringResource(Res.string.network_event_logs),
                subtitle = stringResource(Res.string.network_event_logs_description),
                onClick = { mainVm.navigator.navigate(NetworkEventLogRoute) },
            )
            GkTextSwitch(
                title = stringResource(Res.string.network_log_overlay),
                subtitle = stringResource(Res.string.network_log_overlay_description),
                checked = overlayRunning,
                onCheckedChange = { if (it) NetworkLogOverlay.start() else NetworkLogOverlay.stop() },
            )
            GkPageBottomSpace()
        }
    }
}

@Composable
private fun SniModeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
