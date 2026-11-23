package li.gkd.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.JvmDnsResolver
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json

actual fun createGithubRuleClient(host: String, rule: GithubHostRule): HttpClient {
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
                verifyOriginalHost(host, chain.firstOrNull()
                    ?: throw IllegalStateException("服务器未提供证书"))
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTrustManager.acceptedIssuers
        }
    val systemDns = JvmDnsResolver()
    return HttpClient(CIO) {
        // A redirect changes the HTTP origin. Do not reuse this host-pinned TLS engine for it.
        followRedirects = false
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
        engine {
            dnsResolver = { requestedHost ->
                if (requestedHost.equals(host, ignoreCase = true)) {
                    listOf(rule.ip)
                } else {
                    systemDns(requestedHost)
                }
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
    val valid = if (isIpAddress) {
        ipNames.any { it == host }
    } else {
        dnsNames.any { certificateName ->
            certificateName.equals(host, ignoreCase = true) ||
                (certificateName.startsWith("*.") &&
                    host.endsWith(certificateName.substring(1), ignoreCase = true) &&
                    host.substringBefore('.').isNotEmpty() &&
                    !host.substringBefore('.').contains('.'))
        }
    }
    check(valid) {
        "TLS 证书不包含原始请求域名 $host 的 Subject Alternative Name"
    }
}
