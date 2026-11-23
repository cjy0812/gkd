package li.gkd.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.JvmDnsResolver
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import li.gkd.app.network.installNetworkEventLogging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient

actual fun createGithubRuleClient(
    host: String,
    rule: GithubHostRule,
    useOkHttpSniWorkaround: Boolean,
): HttpClient {
    if (rule.sni.isEmpty() || useOkHttpSniWorkaround) {
        val addresses = rule.resolvedIps.ifEmpty { listOf(rule.ip) }
        val targetHost = rule.sni.ifEmpty { rule.ip }
        val systemTrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                init(null as KeyStore?)
                trustManagers.filterIsInstance<X509TrustManager>().first()
            }
        val okhttp = OkHttpClient.Builder()
            // Let Ktor's HttpRedirect plugin own redirects. This keeps the SNI/Host rewrite
            // scoped to each Ktor request and prevents OkHttp from hiding intermediate hops.
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(Dns { requestedHost ->
                if (requestedHost.equals(targetHost, ignoreCase = true)) {
                    addresses.map(InetAddress::getByName)
                } else {
                    Dns.SYSTEM.lookup(requestedHost)
                }
            })
            .sslSocketFactory(
                SSLContext.getInstance("TLS").apply { init(null, arrayOf(systemTrustManager), null) }.socketFactory,
                systemTrustManager,
            )
            .hostnameVerifier { requestedHost, session ->
                // The rewritten URL host represents the original GitHub request. A different
                // redirect host must be verified against itself, never against the first host.
                val verificationHost =
                    if (requestedHost.equals(targetHost, ignoreCase = true)) host else requestedHost
                HttpsURLConnection.getDefaultHostnameVerifier().verify(verificationHost, session)
            }
            .addInterceptor { chain ->
                val request = chain.request()
                val isOriginalHost = request.url.host.equals(host, ignoreCase = true)
                val rewritten = if (isOriginalHost) {
                    val rewrittenUrl = request.url.newBuilder().host(targetHost).build()
                    request.newBuilder()
                        .url(rewrittenUrl)
                        .header("Host", host)
                        .build()
                } else {
                    // Do not carry the original Host header onto a cross-host redirect.
                    request.newBuilder().removeHeader("Host").build()
                }
                if (isOriginalHost) {
                    NetworkEventLog.info(
                        host,
                        "OkHttp URL-host/DNS rewrite：URL host=$targetHost，HTTP Host=$host，SNI=${rule.sni.ifEmpty { "(empty)" }}",
                    )
                }
                try {
                    chain.proceed(rewritten)
                } catch (error: Exception) {
                    NetworkEventLog.error(
                        request.url.host,
                        "OkHttp 连接失败：${error::class.simpleName}: ${networkFailureSummary(error) ?: "request failed"}",
                    )
                    throw error
                }
            }
            .build()
        NetworkEventLog.info(host, "使用 OkHttp URL-host/DNS workaround；证书链与请求目标域名校验仍开启")
        return HttpClient(OkHttp) {
            installNetworkEventLogging()
            followRedirects = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }, ContentType.Any)
            }
            engine { config { preconfigured = okhttp } }
        }
    }

    val systemTrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    val hostCheckingTrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                systemTrustManager.checkClientTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                systemTrustManager.checkServerTrusted(chain, authType)
                verifyOriginalHost(host, chain.firstOrNull() ?: throw IllegalStateException("服务器未提供证书"))
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
        }
    val systemDns = JvmDnsResolver()
    return HttpClient(CIO) {
        installNetworkEventLogging()
        // CIO's SNI value and explicit original-host certificate check are client-scoped.
        // Keep cross-host redirects disabled here rather than reuse the first host's TLS policy.
        followRedirects = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }, ContentType.Any)
        }
        engine {
            dnsResolver = { requestedHost ->
                if (requestedHost.equals(host, ignoreCase = true)) {
                    (rule.resolvedIps.ifEmpty { listOf(rule.ip) })
                } else systemDns(requestedHost)
            }
            https {
                serverName = rule.sni.takeIf(String::isNotEmpty)
                trustManager = hostCheckingTrustManager
            }
        }
    }
}

private fun verifyOriginalHost(host: String, certificate: X509Certificate) {
    val subjectAltNames = certificate.subjectAlternativeNames.orEmpty()
    val dnsNames = subjectAltNames.mapNotNull { entry ->
        if (entry.size >= 2 && (entry[0] as? Number)?.toInt() == 2) entry[1] as? String else null
    }
    val ipNames = subjectAltNames.mapNotNull { entry ->
        if (entry.size >= 2 && (entry[0] as? Number)?.toInt() == 7) entry[1]?.toString() else null
    }
    val isIpAddress = host.all { it.isDigit() || it == '.' } && host.contains('.')
    val valid = if (isIpAddress) ipNames.any { it == host } else dnsNames.any { certificateName ->
        certificateName.equals(host, ignoreCase = true) ||
            (certificateName.startsWith("*.") &&
                host.endsWith(certificateName.substring(1), ignoreCase = true) &&
                host.dropLast(certificateName.length - 1).let { it.isNotEmpty() && '.' !in it })
    }
    check(valid) { "TLS 证书不包含原始请求域名 $host 的 Subject Alternative Name" }
}
