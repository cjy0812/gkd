package li.gkd.app.network

import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.io.File
import java.net.URI

object UpdateClient {
    suspend fun fetch(url: String): NewVersion {\n        NetworkEventLog.info(url.substringAfter("://").substringBefore("/"), "开始检查更新")\n        return try {\n            NetworkClients.clientFor(url).get(url) { expectSuccess = true }.body<NewVersion>().also {\n                NetworkEventLog.info(url.substringAfter("://").substringBefore("/"), "更新请求成功")\n            }\n        } catch (error: Exception) {\n            NetworkEventLog.error(url.substringAfter("://").substringBefore("/"), "更新请求失败：${error::class.simpleName}: ${error.message}")\n            throw error\n        }\n    }

    suspend fun download(url: String, file: File, size: Long, progress: (Float) -> Unit) {
        var currentUrl = url
        repeat(6) {
            var redirectLocation: String? = null
            NetworkClients.clientFor(currentUrl)
                .prepareGet(currentUrl) {
                    expectSuccess = false
                    onDownload { received, _ -> if (size > 0) progress(received.toFloat() / size) }
                }
                .execute { response ->
                    if (response.status.value in 300..399) {
                        redirectLocation = response.headers[HttpHeaders.Location]
                            ?: error("下载重定向缺少 Location 响应头")
                    } else {
                        check(response.status.value in 200..299) {
                            "下载失败：HTTP ${response.status.value}"
                        }
                        response.bodyAsChannel().copyAndClose(file.writeChannel())
                    }
                }
            val location = redirectLocation ?: return
            currentUrl = URI(currentUrl).resolve(location).toString()
            require(currentUrl.startsWith("https://", ignoreCase = true)) {
                "拒绝跟随非 HTTPS 下载重定向"
            }
        }
        error("下载重定向次数超过限制")
    }
}
