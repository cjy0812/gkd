package li.gkd.app.network

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun probeGithubCertificate(
    host: String,
    ip: String?,
    sni: String?,
): GithubCertificateReport = withContext(Dispatchers.IO) {
    val rawSocket = Socket()
    rawSocket.connect(InetSocketAddress(ip ?: host, 443), 10_000)
    rawSocket.soTimeout = 10_000
    val socket =
        (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(rawSocket, host, 443, true) as SSLSocket
    socket.soTimeout = 10_000
    try {
        val parameters = socket.sslParameters
        parameters.serverNames =
            when {
                sni == null -> listOf(SNIHostName(host))
                sni.isEmpty() -> emptyList()
                else -> listOf(SNIHostName(sni))
            }
        socket.sslParameters = parameters
        socket.startHandshake()
        val session = socket.session
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
            throw SSLPeerUnverifiedException("证书不匹配原始请求域名：$host")
        }
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw SSLPeerUnverifiedException("服务器未提供 X.509 证书")
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val hex = "0123456789ABCDEF"
        val fingerprint = buildString(digest.size * 3 - 1) {
            digest.forEachIndexed { index, byte ->
                if (index > 0) append(':')
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
        val sans = certificate.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
            entry.getOrNull(1)?.toString()
        }.distinct()
        GithubCertificateReport(
            host = host,
            sha256 = fingerprint,
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            subjectAltNames = sans,
            notBefore = certificate.notBefore.toString(),
            notAfter = certificate.notAfter.toString(),
        )
    } finally {
        socket.close()
    }
}
