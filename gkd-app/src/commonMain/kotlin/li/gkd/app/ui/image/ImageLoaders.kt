package li.gkd.app.ui.image

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import li.gkd.app.network.GithubDnsResolver
import li.gkd.app.network.GithubHostRule
import li.gkd.app.network.GithubHostRules
import li.gkd.app.network.NetworkEventLog
import li.gkd.app.network.networkFailureSummary
import li.gkd.app.settings.SettingsRepository
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
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
                        callFactory = { GithubImageCallFactory },
                    ),
                )
                components()
            }
            .build()
}

private object GithubImageCallFactory : Call.Factory {
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    override fun newCall(request: Request): Call {
        val host = request.url.host.lowercase()
        if (!host.isGithubHost()) return loggingClient.newCall(request)
        val settings = SettingsRepository.settings.value
        if (!settings.enableGithubNetworkCompatibility) return loggingClient.newCall(request)
        val rules = runCatching { GithubHostRules.parse(settings.githubHostRulesJson) }
            .onFailure { NetworkEventLog.error(host, "图片规则解析失败：${it.message}") }
            .getOrDefault(emptyList())
        val rule = GithubHostRules.find(rules, host)
        val key = listOf(
            host,
            settings.githubUseOkHttpSniWorkaround,
            (settings.githubEmptySniFallback && !settings.githubUseOkHttpSniWorkaround),
            settings.githubDnsMode,
            settings.githubDnsCustomUrl,
            settings.githubDnsCustomServer,
            rule?.sni.orEmpty(),
            rule?.ip.orEmpty(),
        ).joinToString("|")
        val client = clients.getOrPut(key) {
            createClient(host, rule, settings.githubEmptySniFallback && !settings.githubUseOkHttpSniWorkaround, settings.githubDnsMode, settings.githubDnsCustomUrl, settings.githubDnsCustomServer)
        }
        return client.newCall(request)
    }

    private fun createClient(
        originalHost: String,
        rule: GithubHostRule?,
        emptySniFallback: Boolean,
        dnsMode: String,
        customDnsUrl: String,
        customDnsServer: String,
    ): OkHttpClient {
        val fallbackIps = if (rule == null && emptySniFallback) {
            runCatching { GithubDnsResolver.resolve(originalHost, dnsMode, customDnsUrl, customDnsServer) }
                .getOrElse {
                    NetworkEventLog.warn(originalHost, "图片 DNS 提供商失败，回退系统 DNS：${networkFailureSummary(it) ?: "resolution failed"}")
                    runCatching { InetAddress.getAllByName(originalHost).toList() }.getOrDefault(emptyList())
                }
        } else emptyList()
        val addresses = when {
            rule != null -> rule.resolvedIps.ifEmpty { listOf(rule.ip) }.map(InetAddress::getByName)
            fallbackIps.isNotEmpty() -> fallbackIps
            else -> emptyList()
        }
        val rewrittenHost = when {
            rule != null && rule.sni.isNotEmpty() -> rule.sni
            rule != null -> rule.ip
            emptySniFallback -> fallbackIps.firstOrNull()?.hostAddress
            else -> null
        }
        val dns = Dns { requestedHost ->
            when {
                rewrittenHost != null && requestedHost.equals(rewrittenHost, ignoreCase = true) && addresses.isNotEmpty() -> addresses
                requestedHost.equals(originalHost, ignoreCase = true) && addresses.isNotEmpty() -> addresses
                requestedHost.equals(originalHost, ignoreCase = true) -> runCatching {
                    GithubDnsResolver.resolve(originalHost, dnsMode, customDnsUrl, customDnsServer)
                }.getOrElse {
                    NetworkEventLog.warn(originalHost, "DNS 提供商失败，回退系统 DNS：${networkFailureSummary(it) ?: "resolution failed"}")
                    Dns.SYSTEM.lookup(requestedHost)
                }
                else -> Dns.SYSTEM.lookup(requestedHost)
            }
        }
        return defaultClient.newBuilder()
            .followRedirects(true)
            // The initial request may use a rewritten SNI host, but redirects must use and verify
            // their own host and must not inherit the original request's Host header.
            .followSslRedirects(false)
            .dns(dns)
            .hostnameVerifier { hostname, session ->
                val verifiedHost =
                    if (rewrittenHost != null && hostname.equals(rewrittenHost, ignoreCase = true)) {
                        originalHost
                    } else hostname
                HttpsURLConnection.getDefaultHostnameVerifier().verify(verifiedHost, session)
            }
            .addInterceptor { chain ->
                val original = chain.request()
                val actualHost = original.url.host
                val shouldRewrite = actualHost.equals(originalHost, ignoreCase = true) && rewrittenHost != null
                val rewritten = if (shouldRewrite) {
                    original.newBuilder()
                        .url(original.url.newBuilder().host(rewrittenHost!!).build())
                        .header("Host", originalHost)
                        .build()
                } else {
                    original
                }
                NetworkEventLog.info(
                    originalHost,
                    when {
                        rule != null -> "预览图命中规则：SNI=${rule.sni.ifEmpty { "(empty)" }}，IP=${rule.ip}"
                        emptySniFallback && rewrittenHost != null -> "预览图无规则，DNS 空 SNI：$rewrittenHost"
                        else -> "预览图使用 DNS=$dnsMode，原始 SNI"
                    },
                )
                try {
                    chain.proceed(rewritten)
                } catch (error: Exception) {
                    val requestHost = original.url.host
                    val eventHost =
                        if (shouldRewrite) originalHost else requestHost
                    NetworkEventLog.request(
                        host = eventHost,
                        method = original.method,
                        url = displayImageUrl(original, originalHost, rewrittenHost),
                        error = "${error.javaClass.simpleName}: ${networkFailureSummary(error) ?: "request failed"}",
                        source = "IMAGE",
                    )
                    throw error
                }
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val isRewrittenHost =
                    rewrittenHost != null && request.url.host.equals(rewrittenHost, ignoreCase = true)
                val adjusted =
                    if (isRewrittenHost) {
                        request.newBuilder().header("Host", originalHost).build()
                    } else {
                        request.newBuilder().removeHeader("Host").build()
                    }
                val response = chain.proceed(adjusted)
                val eventHost = if (isRewrittenHost) originalHost else request.url.host
                NetworkEventLog.request(
                    host = eventHost,
                    method = request.method,
                    url = displayImageUrl(request, originalHost, rewrittenHost),
                    status = response.code,
                    redirectLocation = response.header("Location"),
                    source = "IMAGE",
                )
                response
            }
            .build()
    }

    private fun displayImageUrl(request: Request, originalHost: String, rewrittenHost: String?): String {
        if (rewrittenHost == null || !request.url.host.equals(rewrittenHost, ignoreCase = true)) {
            return request.url.toString()
        }
        return request.url.newBuilder().host(originalHost).build().toString()
    }

    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val loggingClient = defaultClient.newBuilder()
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (error: Exception) {
                val request = chain.request()
                NetworkEventLog.request(
                    host = request.url.host,
                    method = request.method,
                    url = request.url.toString(),
                    error = "${error.javaClass.simpleName}: ${networkFailureSummary(error) ?: "request failed"}",
                    source = "IMAGE",
                )
                throw error
            }
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            NetworkEventLog.request(
                host = request.url.host,
                method = request.method,
                url = request.url.toString(),
                status = response.code,
                redirectLocation = response.header("Location"),
                source = "IMAGE",
            )
            response
        }
        .build()
}

private fun String.isGithubHost(): Boolean =
    this == "github.com" || endsWith(".github.com") ||
        this == "githubusercontent.com" || endsWith(".githubusercontent.com") ||
        this == "githubassets.com" || endsWith(".githubassets.com")
