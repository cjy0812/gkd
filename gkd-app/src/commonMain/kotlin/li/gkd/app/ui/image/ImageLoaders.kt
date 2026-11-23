package li.gkd.app.ui.image

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import li.gkd.app.network.GithubDnsResolver
import li.gkd.app.network.GithubHostRules
import li.gkd.app.network.NetworkEventLog
import li.gkd.app.settings.SettingsRepository
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

object ImageLoaders {
    fun create(
        context: PlatformContext,
        cache: File,
        components: ComponentRegistry.Builder.() -> Unit = {},
    ) =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder().directory(cache.toOkioPath()).maxSizePercent(0.1).build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            createNetworkClient()
                        },
                    ),
                )
                components()
            }
            .build()

    private fun createNetworkClient(): OkHttpClient {
        val store = SettingsRepository.settings.value
        val rules = if (store.githubHostRulesJson.isBlank()) emptyList()
            else runCatching { GithubHostRules.parse(store.githubHostRulesJson) }
                .onFailure { NetworkEventLog.error("github.com", "图片规则解析失败：${it.message}") }
                .getOrDefault(emptyList())
        val selectedRule = ThreadLocal<GithubHostRulesMatch?>()
        val dns = Dns { requestedHost ->
            if (!store.enableGithubNetworkCompatibility || !requestedHost.isGithubHost()) {
                Dns.SYSTEM.lookup(requestedHost)
            } else {
                val rule = GithubHostRules.find(rules, requestedHost)
                if (rule != null) {
                    (rule.resolvedIps.ifEmpty { listOf(rule.ip) }).map(InetAddress::getByName)
                } else {
                    GithubDnsResolver.resolve(requestedHost, store.githubDnsMode, store.githubDnsCustomUrl)
                }
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(dns)
            .addInterceptor { chain ->
                val original = chain.request()
                val originalHost = original.url.host
                if (!store.enableGithubNetworkCompatibility || !originalHost.isGithubHost()) {
                    val response = try {
                        chain.proceed(original)
                    } catch (error: Exception) {
                        NetworkEventLog.request(originalHost, original.method, original.url.toString(), error = error.javaClass.simpleName)
                        throw error
                    }
                    NetworkEventLog.request(originalHost, original.method, original.url.toString(), status = response.code)
                    return@addInterceptor response
                }

                val rule = GithubHostRules.find(rules, originalHost)
                val noRuleEmptySni = rule == null && store.githubEmptySniFallback
                val targetIp = if (noRuleEmptySni) {
                    runCatching {
                        GithubDnsResolver.resolve(originalHost, store.githubDnsMode, store.githubDnsCustomUrl)
                            .firstOrNull()?.hostAddress
                    }.getOrNull()
                } else null
                val rewrittenHost = when {
                    rule != null && rule.sni.isNotEmpty() -> rule.sni
                    rule != null -> rule.ip
                    noRuleEmptySni -> targetIp
                    else -> null
                }
                val rewritten = if (rewrittenHost != null) {
                    original.newBuilder()
                        .url(original.url.newBuilder().host(rewrittenHost).build())
                        .header("Host", originalHost)
                        .build()
                } else original
                NetworkEventLog.info(
                    originalHost,
                    when {
                        rule != null -> "图片请求命中规则：SNI=${rule.sni.ifEmpty { "(empty)" }}，IP=${rule.ip}"
                        noRuleEmptySni && targetIp != null -> "图片请求无规则，DNS 空 SNI 兜底：$targetIp"
                        noRuleEmptySni -> "图片请求无规则，DNS 空 SNI 解析失败"
                        else -> "图片请求使用配置 DNS 与原始 SNI"
                    },
                )
                try {
                    val response = chain.proceed(rewritten)
                    NetworkEventLog.request(originalHost, original.method, original.url.toString(), status = response.code)
                    response
                } catch (error: Exception) {
                    NetworkEventLog.request(originalHost, original.method, original.url.toString(), error = error.javaClass.simpleName)
                    throw error
                }
            }
            .hostnameVerifier { _, session ->
                val originalHost = session.peerHost.takeIf { it.isGithubHost() }
                if (originalHost == null) javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(session.peerHost, session)
                else javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(originalHost, session)
            }
            .build()
    }
}

private fun String.isGithubHost(): Boolean =
    this == "github.com" || endsWith(".github.com") ||
        this == "githubusercontent.com" || endsWith(".githubusercontent.com")

private data class GithubHostRulesMatch(val host: String, val rule: String)
