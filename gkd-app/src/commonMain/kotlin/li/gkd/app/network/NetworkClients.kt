package li.gkd.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import li.gkd.app.settings.SettingsRepository

object NetworkClients {
    private val sharedClient = lazy { create() }
    private val githubClients = mutableMapOf<String, HttpClient>()
    val client: HttpClient
        get() = sharedClient.value

    /** Select a host-scoped client only for an enabled, imported GitHub rule. */
    @Synchronized
    fun clientFor(url: String): HttpClient {
        val store = SettingsRepository.settings.value
        if (!store.enableGithubNetworkCompatibility || store.githubHostRulesJson.isBlank()) return client
        val requestUrl = runCatching { Url(url) }.getOrNull() ?: return client
        if (requestUrl.protocol.name != "https") return client
        val host = requestUrl.host.lowercase()
        val rules = runCatching { GithubHostRules.parse(store.githubHostRulesJson) }.getOrNull() ?: return client
        val rule = GithubHostRules.find(rules, host) ?: return client
        val key = listOf(host, rule.sni, rule.ip).joinToString("|")
        return githubClients.getOrPut(key) { createGithubRuleClient(host, rule) }
    }

    @Synchronized
    fun close() {
        if (sharedClient.isInitialized()) sharedClient.value.close()
        githubClients.values.forEach(HttpClient::close)
        githubClients.clear()
    }

    private fun create() =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                    ContentType.Any,
                )
            }
            engine { clientCacheSize = 0 }
        }
}
