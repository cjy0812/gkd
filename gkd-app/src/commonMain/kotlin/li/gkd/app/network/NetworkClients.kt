package li.gkd.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import li.gkd.app.settings.SettingsRepository

object NetworkClients {
    private val sharedClient = lazy { create() }
    private val githubClients = mutableMapOf<String, HttpClient>()
    val client: HttpClient
        get() = sharedClient.value

    /**
     * Selects an imported rule when available. With compatibility enabled but no matching rule,
     * resolve the original GitHub hostname and try the resulting IP with empty SNI.
     */
    suspend fun clientFor(url: String): HttpClient {
        val requestUrl = runCatching { Url(url) }.getOrNull() ?: return client
        if (requestUrl.protocol.name != "https") return client
        val host = requestUrl.host.lowercase()
        if (!host.isGithubHost()) return client
        val store = SettingsRepository.settings.value
        if (!store.enableGithubNetworkCompatibility) return client

        val rules =
            if (store.githubHostRulesJson.isBlank()) emptyList()
            else {
                runCatching { GithubHostRules.parse(store.githubHostRulesJson) }
                    .onFailure { NetworkEventLog.error(host, "规则解析失败：${it.message}") }
                    .getOrDefault(emptyList())
            }
        val rule = GithubHostRules.find(rules, host)
        if (rule != null) {
            NetworkEventLog.info(host, "命中规则：SNI=${rule.sni.ifEmpty { "(empty)" }}，IP=${rule.ip}")
            val key = listOf(
                host,
                rule.sni,
                rule.ip,
                rule.resolvedIps.joinToString(","),
                store.githubUseOkHttpSniWorkaround.toString(),
            ).joinToString("|")
            return cachedClient(key) {
                createGithubRuleClient(host, rule, store.githubUseOkHttpSniWorkaround)
            }
        }

        // No rule: resolve the actual host using the system resolver, then pin those addresses.
        val addresses = try {
            withContext(Dispatchers.IO) {
                InetAddress.getAllByName(host).map { it.hostAddress.substringBefore('%') }.distinct()
            }
        } catch (error: Exception) {
            NetworkEventLog.error(host, "系统 DNS 解析失败：${error.message}")
            return client
        }
        val ipv4 = addresses.filter { it.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) }
        if (ipv4.isEmpty()) {
            NetworkEventLog.warn(host, "DNS 未返回 IPv4，保持系统默认连接")
            return client
        }
        val fallbackRule = GithubHostRule(
            domains = listOf(host),
            sni = "",
            ip = ipv4.first(),
            resolvedIps = ipv4,
        )
        NetworkEventLog.warn(host, "无匹配规则，DNS 兜底：IP=${ipv4.joinToString()}，尝试空 SNI")
        val key = listOf(host, "auto-empty-sni", ipv4.joinToString(",")).joinToString("|")
        return cachedClient(key) { createGithubRuleClient(host, fallbackRule, false) }
    }

    private fun cachedClient(key: String, create: () -> HttpClient): HttpClient =
        synchronized(githubClients) { githubClients.getOrPut(key, create) }

    @Synchronized
    fun close() {
        if (sharedClient.isInitialized()) sharedClient.value.close()
        synchronized(githubClients) {
            githubClients.values.forEach(HttpClient::close)
            githubClients.clear()
        }
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

private fun String.isGithubHost(): Boolean =
    this == "github.com" || endsWith(".github.com") ||
        this == "githubusercontent.com" || endsWith(".githubusercontent.com")
