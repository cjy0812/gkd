package li.gkd.app.network

import io.ktor.client.HttpClient

/**
 * Platform HTTP engine for a single original host + rule pair.
 *
 * The engine is scoped to one host because Ktor CIO's SNI is engine-wide.
 */
expect fun createGithubRuleClient(\n    host: String,\n    rule: GithubHostRule,\n    useOkHttpSniWorkaround: Boolean,\n): HttpClient
