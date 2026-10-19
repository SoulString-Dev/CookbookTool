package org.miu.cookbook.data.sync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.miu.cookbook.sync.model.ApiError
import org.miu.cookbook.sync.model.BlobCheckRequest
import org.miu.cookbook.sync.model.BlobCheckResponse
import org.miu.cookbook.sync.model.HealthResponse
import org.miu.cookbook.sync.model.PairRequest
import org.miu.cookbook.sync.model.PairResponse
import org.miu.cookbook.sync.model.PullResponse
import org.miu.cookbook.sync.model.PushRequest
import org.miu.cookbook.sync.model.PushResponse
import org.miu.cookbook.sync.model.SyncJson
import org.miu.cookbook.sync.model.SyncProtocol
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 服务端明确回了错误（带 HTTP 状态码与错误码），别和网络层异常混着看。 */
class SyncHttpException(
    val status: Int,
    val code: String,
    message: String = "",
) : Exception(message.ifEmpty { code })

/**
 * 同步接口的客户端。**用 JDK 自带的 HttpURLConnection**，不引 Ktor/OkHttp：
 * 这台开发机拉不到新依赖，而这些请求也就六种，手写比引一个框架便宜。
 *
 * 一个实例对应一次同步；服务端地址或令牌变了就换新的。
 */
class SyncApi(
    baseUrl: String,
    private val token: String = "",
) {

    private val base = baseUrl.trim().trimEnd('/')

    fun health(): HealthResponse = decode(
        call(SyncProtocol.PATH_HEALTH, "GET", null),
    )

    /** 配对：不需要令牌（这时候还没有），但要配对码。 */
    fun pair(code: String, deviceName: String): PairResponse = decode(
        call(
            SyncProtocol.PATH_PAIR,
            "POST",
            SyncJson.json.encodeToString(PairRequest(code = code, deviceName = deviceName)).toByteArray(),
        ),
    )

    fun pull(since: Long, limit: Int = SyncProtocol.MAX_PULL_LIMIT): PullResponse = decode(
        call("${SyncProtocol.PATH_SYNC}?since=$since&limit=$limit", "GET", null),
    )

    fun push(request: PushRequest): PushResponse = decode(
        call(SyncProtocol.PATH_SYNC, "POST", SyncJson.json.encodeToString(request).toByteArray()),
    )

    /** 批量问「这些图你有吗」，返回**缺的**那些（已存在的不必再传）。 */
    fun missingBlobs(hashes: Collection<String>): List<String> {
        if (hashes.isEmpty()) return emptyList()
        val response: BlobCheckResponse = decode(
            call(
                SyncProtocol.PATH_BLOB_CHECK,
                "POST",
                SyncJson.json.encodeToString(BlobCheckRequest(sha256 = hashes.toList())).toByteArray(),
            ),
        )
        return response.missing
    }

    fun putBlob(sha256: String, bytes: ByteArray) {
        call(SyncProtocol.blob(sha256), "PUT", bytes, contentType = "image/jpeg")
    }

    /** 取一张图。服务端没有（或已被清理）时返回 null，调用方跳过这张图就行。 */
    fun getBlob(sha256: String): ByteArray? =
        call(SyncProtocol.blob(sha256), "GET", null, maxBytes = SyncProtocol.MAX_BLOB_BYTES, allowNotFound = true)

    // ------------------------------------------------------------ 底层

    private inline fun <reified T> decode(bytes: ByteArray?): T =
        SyncJson.json.decodeFromString(bytes?.decodeToString() ?: "{}")

    private fun call(
        path: String,
        method: String,
        body: ByteArray?,
        contentType: String = "application/json; charset=utf-8",
        maxBytes: Long = SyncProtocol.MAX_JSON_BYTES,
        allowNotFound: Boolean = false,
    ): ByteArray? {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            // 首次同步要一口气传几百张图，读超时给宽一点
            readTimeout = 60_000
            instanceFollowRedirects = false
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND && allowNotFound) return null
            val stream: InputStream? = if (status in 200..299) {
                runCatching { connection.inputStream }.getOrNull()
            } else {
                connection.errorStream
            }
            val bytes = stream?.use { it.readAtMost(maxBytes) } ?: ByteArray(0)
            if (status !in 200..299) {
                val error = runCatching { SyncJson.json.decodeFromString<ApiError>(bytes.decodeToString()) }.getOrNull()
                throw SyncHttpException(status, error?.error ?: "http_$status", error?.message.orEmpty())
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    /** 读最多 [maxBytes]，超了就报错——服务端要是回个几个 G 的东西，不能把它整个读进内存。 */
    private fun InputStream.readAtMost(maxBytes: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "响应体超过上限（$maxBytes 字节）" }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}
