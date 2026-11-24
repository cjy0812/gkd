package li.gkd.app.network

import kotlinx.serialization.Serializable

@Serializable
data class GithubCertificateReport(
    val host: String,
    val sha256: String,
    val subject: String,
    val issuer: String,
    val subjectAltNames: List<String>,
    val notBefore: String,
    val notAfter: String,
)

/**
 * Performs a normal system-trusted TLS handshake and verifies the certificate against [host].
 * A null SNI argument uses the host's normal SNI; an empty string explicitly suppresses SNI.
 */
expect suspend fun probeGithubCertificate(
    host: String,
    ip: String?,
    sni: String?,
): GithubCertificateReport
