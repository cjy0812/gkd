package li.gkd.app.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.security.cert.X509Certificate
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response

private data class NetworkCallContext(
    val requestId: Long?,
    val source: String,
    val host: String,
    val method: String,
    val url: String,
    val startedAt: Long?,
    val sni: String?,
)

object NetworkOkHttpDiagnostics : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val request = call.request()
        return Listener(
            requestId = request.header(GKD_NETWORK_REQUEST_ID_HEADER)?.toLongOrNull(),
            source = request.header(GKD_NETWORK_REQUEST_SOURCE_HEADER) ?: "HTTP",
            host = request.header(GKD_NETWORK_REQUEST_HOST_HEADER) ?: request.url.host,
            method = request.method,
            url = request.url.toString(),
            startedAt = request.header(GKD_NETWORK_REQUEST_STARTED_HEADER)?.toLongOrNull(),
            sni = request.header(GKD_NETWORK_REQUEST_SNI_HEADER),
        )
    }

    val applicationInterceptor = Interceptor { chain ->
        val request = chain.request()
        val context = NetworkCallContext(
            requestId = request.header(GKD_NETWORK_REQUEST_ID_HEADER)?.toLongOrNull(),
            source = request.header(GKD_NETWORK_REQUEST_SOURCE_HEADER) ?: "HTTP",
            host = request.header(GKD_NETWORK_REQUEST_HOST_HEADER) ?: request.url.host,
            method = request.method,
            url = request.url.toString(),
            startedAt = request.header(GKD_NETWORK_REQUEST_STARTED_HEADER)?.toLongOrNull(),
            sni = request.header(GKD_NETWORK_REQUEST_SNI_HEADER),
        )
        val cleanRequest = request.newBuilder()
            .removeHeader(GKD_NETWORK_REQUEST_ID_HEADER)
            .removeHeader(GKD_NETWORK_REQUEST_STARTED_HEADER)
            .removeHeader(GKD_NETWORK_REQUEST_SOURCE_HEADER)
            .removeHeader(GKD_NETWORK_REQUEST_HOST_HEADER)
            .removeHeader(GKD_NETWORK_REQUEST_SNI_HEADER)
            .tag(NetworkCallContext::class.java, context)
            .build()
        chain.proceed(cleanRequest)
    }

    val imageNetworkInterceptor = Interceptor { chain ->
        val request = chain.request()
        val context = request.tag(NetworkCallContext::class.java)
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            NetworkEventLog.requestFailed(
                host = context?.host ?: request.url.host,
                method = request.method,
                url = context?.url ?: request.url.toString(),
                error = error,
                requestId = context?.requestId,
                startedAt = context?.startedAt,
                source = context?.source ?: "IMAGE",
            )
            throw error
        }
        NetworkEventLog.response(
            host = context?.host ?: request.url.host,
            method = request.method,
            url = context?.url ?: request.url.toString(),
            status = response.code,
            redirectLocation = response.header("Location"),
            requestId = context?.requestId,
            startedAt = context?.startedAt,
            source = context?.source ?: "IMAGE",
        )
        response
    }

    private class Listener(
        private val requestId: Long?,
        private val source: String,
        private val host: String,
        private val method: String,
        private val url: String,
        private val startedAt: Long?,
    ) : EventListener() {
        private var dnsStartedAt: Long? = null
        private var connectStartedAt: Long? = null
        private var tlsStartedAt: Long? = null

        override fun dnsStart(call: Call, domainName: String) {
            dnsStartedAt = System.currentTimeMillis()
            NetworkEventLog.info(
                host,
                "Socket DNS 查询开始：$domainName",
                source = "NETWORK",
                phase = "SOCKET_DNS_START",
                requestId = requestId,
            )
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
            val addresses = inetAddressList.map { it.hostAddress.substringBefore('%') }.distinct()
            val duration = dnsStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
            NetworkEventLog.info(
                host,
                "Socket DNS 查询完成：$domainName → ${addresses.joinToString()}",
                source = "NETWORK",
                phase = "SOCKET_DNS_END",
                requestId = requestId,
                resolvedIps = addresses,
                durationMs = duration,
                sni = sni,
            )
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStartedAt = System.currentTimeMillis()
            NetworkEventLog.info(
                host,
                "TCP 连接开始：${inetSocketAddress.hostString}:${inetSocketAddress.port}，proxy=$proxy",
                source = "NETWORK",
                phase = "TCP_CONNECT_START",
                requestId = requestId,
                selectedIp = inetSocketAddress.address?.hostAddress?.substringBefore('%'),
                sni = sni,
            )
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            val duration = connectStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
            NetworkEventLog.info(
                host,
                "TCP 连接建立：${inetSocketAddress.hostString}:${inetSocketAddress.port}，protocol=${protocol ?: "unknown"}",
                source = "NETWORK",
                phase = "TCP_CONNECT_END",
                requestId = requestId,
                selectedIp = inetSocketAddress.address?.hostAddress?.substringBefore('%'),
                durationMs = duration,
            )
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            val duration = connectStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
            NetworkEventLog.error(
                host,
                "TCP 连接失败：${inetSocketAddress.hostString}:${inetSocketAddress.port}，${ioe.message ?: ioe::class.simpleName}",
                source = "NETWORK",
                phase = "TCP_CONNECT_FAILED",
                requestId = requestId,
                errorType = ioe::class.simpleName,
                errorChain = ioe.stackTraceToString().take(4_000),
                durationMs = duration,
            )
        }

        override fun secureConnectStart(call: Call) {
            tlsStartedAt = System.currentTimeMillis()
            NetworkEventLog.info(
                host,
                "TLS 握手开始",
                source = "NETWORK",
                phase = "TLS_HANDSHAKE_START",
                requestId = requestId,
            )
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            val certificate = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
            val fingerprint = certificate?.let {
                MessageDigest.getInstance("SHA-256").digest(it.encoded)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            val duration = tlsStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
            NetworkEventLog.info(
                host,
                "TLS 握手完成：${handshake?.tlsVersion}，cipher=${handshake?.cipherSuite}",
                source = "NETWORK",
                phase = "TLS_HANDSHAKE_END",
                requestId = requestId,
                durationMs = duration,
                sni = sni,
                tlsProtocol = handshake?.tlsVersion?.javaName,
                peerSubject = certificate?.subjectX500Principal?.name,
                certificateSha256 = fingerprint,
            )
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            val socketAddress = connection.route().socketAddress
            NetworkEventLog.info(
                host,
                "连接已分配：${socketAddress.hostString}:${socketAddress.port}，protocol=${connection.protocol()}",
                source = "NETWORK",
                phase = "CONNECTION_ACQUIRED",
                requestId = requestId,
                selectedIp = socketAddress.address?.hostAddress?.substringBefore('%'),
                sni = sni,
            )
        }

        override fun callEnd(call: Call) {
            val duration = startedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
            NetworkEventLog.info(
                host,
                "OkHttp Call 完成：$method $url",
                source = "NETWORK",
                phase = "CALL_END",
                requestId = requestId,
                durationMs = duration,
            )
        }

        override fun callFailed(call: Call, ioe: IOException) {
            val duration = startedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
            NetworkEventLog.error(
                host,
                "OkHttp Call 失败：${ioe.message ?: ioe::class.simpleName}",
                source = "NETWORK",
                phase = "CALL_FAILED",
                requestId = requestId,
                errorType = ioe::class.simpleName,
                errorChain = ioe.stackTraceToString().take(4_000),
                durationMs = duration,
            )
        }
    }
}
