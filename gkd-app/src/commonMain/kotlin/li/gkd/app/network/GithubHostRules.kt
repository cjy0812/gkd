package li.gkd.app.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A GitHub-only projection of the Cealing-Host tuple format:
 * [[domains], sni, ip]. Non-GitHub entries are deliberately ignored.
 */
data class GithubHostRule(
    val domains: List<String>,
    val sni: String,
    val ip: String,
) {
    fun matches(host: String): Boolean = domains.any { pattern ->
        val normalized = host.trimEnd('.').lowercase()
        when {
            pattern.startsWith("*") -> {
                val suffix = pattern.removePrefix("*")
                if (suffix.startsWith(".")) normalized.endsWith(suffix)
                else normalized == suffix || normalized.endsWith(".$suffix")
            }
            else -> normalized == pattern
        }
    }

    val specificity: Int
        get() = domains.maxOfOrNull { it.removePrefix("*").length } ?: 0
}

object GithubHostRules {
    private val json = Json { isLenient = false }

    /**
     * Parses the upstream tuple format and keeps only GitHub-owned host patterns.
     * Invalid rows fail the import instead of silently producing a partial rule set.
     */
    fun parse(text: String): List<GithubHostRule> {
        val rows = json.parseToJsonElement(text).jsonArray
        val parsed = rows.mapIndexedNotNull { index, row ->
            require(row is JsonArray && row.size == 3) {
                "第 ${index + 1} 条规则必须是 [domains, sni, ip]"
            }
            val domainsElement = row[0]
            require(domainsElement is JsonArray) {
                "第 ${index + 1} 条规则的 domains 必须是数组"
            }
            val domains = domainsElement.map { element ->
                element.jsonPrimitive.takeIf { it.isString }?.content?.trim()?.lowercase()
                    ?.takeIf(String::isNotEmpty)
                    ?: error("第 ${index + 1} 条规则包含空域名或非字符串域名")
            }
            val githubDomains = domains.filter { domain ->
                if (domain.contains("github.com") || domain.contains("githubusercontent.com")) {
                    require(isGithubDomainPattern(domain)) {
                        "第 ${index + 1} 条规则包含不支持的 GitHub 域名模式：$domain"
                    }
                    true
                } else {
                    false
                }
            }
            if (githubDomains.isEmpty()) {
                null
            } else {
                val sniValue = row[1].jsonPrimitive
                val ipValue = row[2].jsonPrimitive
                require(sniValue.isString && ipValue.isString) {
                    "第 ${index + 1} 条规则的 SNI 和 IP 必须是字符串"
                }
                val sni = sniValue.content
                val ip = ipValue.content
                require(ip.isValidIpv4()) {
                    "第 ${index + 1} 条 GitHub 规则的 IP 无效：$ip"
                }
                require(sni.isEmpty() || sni.isValidHostname()) {
                    "第 ${index + 1} 条 GitHub 规则的 SNI 无效：$sni"
                }
                GithubHostRule(githubDomains, sni, ip)
            }
        }
        return parsed
            .distinctBy { Triple(it.domains.sorted().joinToString(","), it.sni, it.ip) }
            .sortedByDescending(GithubHostRule::specificity)
    }

    fun encode(rules: List<GithubHostRule>): String =
        JsonArray(
            rules.map { rule ->
                JsonArray(
                    listOf(
                        JsonArray(rule.domains.map(::JsonPrimitive)),
                        JsonPrimitive(rule.sni),
                        JsonPrimitive(rule.ip),
                    ),
                )
            },
        ).toString()

    fun find(rules: List<GithubHostRule>, host: String): GithubHostRule? =
        rules.firstOrNull { it.matches(host) }

    private fun isGithubDomainPattern(value: String): Boolean {
        if (value.count { it == '*' } > 1) return false
        if (value.contains('*') && !value.startsWith("*")) return false
        val domain = value.removePrefix("*").removePrefix(".")
        val isGithubDomain =
            domain == "github.com" ||
                domain.endsWith(".github.com") ||
                domain == "githubusercontent.com" ||
                domain.endsWith(".githubusercontent.com")
        return isGithubDomain && domain.isValidHostname()
    }

    private fun String.isValidIpv4(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true &&
                (part == "0" || !part.startsWith("0"))
        }
    }

    private fun String.isValidHostname(): Boolean {
        if (length > 253 || startsWith('.') || endsWith('.')) return false
        return split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
