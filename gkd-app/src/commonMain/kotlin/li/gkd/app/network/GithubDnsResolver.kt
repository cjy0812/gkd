package li.gkd.app.network

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URLEncoder
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object GithubDnsResolver {
    private const val CLOUDFLARE = "https://cloudflare-dns.com/dns-query?name={name}&type=A"
    private const val GOOGLE = "https://dns.google/resolve?name={name}&type=A"
    private const val ADGUARD = "https://dns.adguard-dns.com/resolve?name={name}&type=A"

    fun resolve(host: String, mode: String, customUrl: String): List<InetAddress> {
        if (mode == "system") return InetAddress.getAllByName(host).toList()
        val template = when (mode) {
            "cloudflare" -> CLOUDFLARE
            "google" -> GOOGLE
            "adguard" -> ADGUARD
            "custom" -> customUrl.takeIf(String::isNotBlank)
                ?: error("自定义 DoH 地址为空")
            else -> error("未知 DNS 模式：$mode")
        }
        val encodedHost = URLEncoder.encode(host, "UTF-8")
        val endpoint = template
            .replace("{name}", encodedHost)
            .replace("{type}", "A")
        require(endpoint.startsWith("https://", ignoreCase = true)) { "DoH 必须使用 HTTPS" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "GKD-Network-Compatibility")
        }
        try {
            check(connection.responseCode in 200..299) { "DoH HTTP ${connection.responseCode}" }
            val json = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val answers = json["Answer"]?.jsonArray.orEmpty()
            val ips = answers.mapNotNull { answer ->
                val item = answer.jsonObject
                if (item["type"]?.jsonPrimitive?.content != "1") return@mapNotNull null
                item["data"]?.jsonPrimitive?.content
            }.filter { it.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) }
            return ips.distinct().map(InetAddress::getByName)
        } finally {
            connection.disconnect()
        }
    }
}
