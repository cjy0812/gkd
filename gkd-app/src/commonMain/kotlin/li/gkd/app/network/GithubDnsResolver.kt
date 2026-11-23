package li.gkd.app.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object GithubDnsResolver {
    private const val CLOUDFLARE = "https://cloudflare-dns.com/dns-query?name={name}&type=A"
    private const val GOOGLE = "https://dns.google/resolve?name={name}&type=A"
    private const val ADGUARD = "https://dns.adguard-dns.com/resolve?name={name}&type=A"

    fun resolve(
        host: String,
        mode: String,
        customUrl: String,
        customServer: String = "1.1.1.1",
    ): List<InetAddress> {
        if (mode == "system") return InetAddress.getAllByName(host).toList()
        if (mode == "custom-dns") return resolveUdp(host, customServer)
        val template = when (mode) {
            "cloudflare" -> CLOUDFLARE
            "google" -> GOOGLE
            "adguard" -> ADGUARD
            "custom", "custom-doh" -> customUrl.takeIf(String::isNotBlank)
                ?: error("自定义 DoH 地址为空")
            else -> error("未知 DNS 模式：$mode")
        }
        val encodedHost = URLEncoder.encode(host, "UTF-8")
        val endpoint = template.replace("{name}", encodedHost).replace("{type}", "A")
        require(endpoint.startsWith("https://", ignoreCase = true)) { "DoH 必须使用 HTTPS" }
        NetworkEventLog.info(host, "DNS 查询：$mode")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "GKD-Network-Compatibility")
        }
        try {
            val status = connection.responseCode
            NetworkEventLog.request("dns:$mode", "GET", endpoint, status = status)
            check(status in 200..299) { "DoH HTTP $status" }
            val responseText = connection.inputStream.bufferedReader().use { it.readText().also { body ->
                check(body.length <= 64 * 1024) { "DoH 响应超过 64 KiB" }
            } }
            val json = Json.parseToJsonElement(responseText).jsonObject
            check(json["Status"]?.jsonPrimitive?.content?.toIntOrNull() == 0) {
                "DoH 返回 DNS 错误状态"
            }
            val answers = json["Answer"]?.jsonArray.orEmpty()
            val ips = answers.mapNotNull { answer ->
                val item = answer.jsonObject
                if (item["type"]?.jsonPrimitive?.content != "1") return@mapNotNull null
                item["data"]?.jsonPrimitive?.content
            }.filter { it.isValidIpv4() }
            return ips.distinct().map(InetAddress::getByName)
        } catch (error: Exception) {
            NetworkEventLog.request("dns:$mode", "GET", endpoint, error = error.javaClass.simpleName)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUdp(host: String, server: String): List<InetAddress> {
        require(server.isValidIpv4()) { "自定义 DNS 必须填写 IPv4 地址" }
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val query = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeShort(id)
                output.writeShort(0x0100)
                output.writeShort(1)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
                host.trimEnd('.').split('.').forEach { label ->
                    val bytes = label.toByteArray(Charsets.US_ASCII)
                    require(bytes.isNotEmpty() && bytes.size <= 63) { "DNS 域名标签无效" }
                    output.writeByte(bytes.size)
                    output.write(bytes)
                }
                output.writeByte(0)
                output.writeShort(1)
                output.writeShort(1)
            }
        }.toByteArray()
        NetworkEventLog.info(host, "DNS 查询：自定义服务器 $server")
        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val bytes = packet.data
            val length = packet.length
            check(length >= 12 && u16(bytes, 0) == id) { "DNS 响应 ID 不匹配" }
            check((u16(bytes, 2) and 0x8000) != 0 && (u16(bytes, 2) and 0x000F) == 0) {
                "DNS 服务器返回错误状态"
            }
            check((u16(bytes, 2) and 0x0200) == 0) { "DNS UDP 响应被截断" }
            val questionCount = u16(bytes, 4)
            val answerCount = u16(bytes, 6)
            var offset = 12
            repeat(questionCount) {
                offset = skipName(bytes, length, offset) + 4
                check(offset <= length) { "DNS Question 越界" }
            }
            val addresses = buildList {
                repeat(answerCount) {
                    offset = skipName(bytes, length, offset)
                    check(offset + 10 <= length) { "DNS Answer 头部不完整" }
                    val type = u16(bytes, offset)
                    val clazz = u16(bytes, offset + 2)
                    val dataLength = u16(bytes, offset + 8)
                    offset += 10
                    check(offset + dataLength <= length) { "DNS Answer 数据不完整" }
                    if (type == 1 && clazz == 1 && dataLength == 4) {
                        add(InetAddress.getByAddress(bytes.copyOfRange(offset, offset + 4)))
                    }
                    offset += dataLength
                }
            }.distinct()
            NetworkEventLog.request("dns:custom-dns", "UDP", "$server:53/$host")
            return addresses
        }
    }

    private fun skipName(bytes: ByteArray, length: Int, start: Int): Int {
        var offset = start
        while (offset < length) {
            val size = bytes[offset].toInt() and 0xFF
            if (size == 0) return offset + 1
            if ((size and 0xC0) == 0xC0) return offset + 2
            check((size and 0xC0) == 0) { "DNS 标签编码无效" }
            offset += size + 1
        }
        error("DNS 域名字段不完整")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun String.isValidIpv4(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true &&
                (part == "0" || !part.startsWith("0"))
        }
    }
}
