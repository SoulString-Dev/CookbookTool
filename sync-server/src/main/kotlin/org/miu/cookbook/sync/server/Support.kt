package org.miu.cookbook.sync.server

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.encodeToString
import org.miu.cookbook.sync.model.ApiError
import org.miu.cookbook.sync.model.SyncJson
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- 小工具

private val LOG_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** 单进程的小服务端，直接 println 就够；JUL 的配置反而更难在容器里看清。 */
fun log(message: String) {
    println("${LocalDateTime.now().format(LOG_STAMP)} $message")
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

private const val HEX = "0123456789abcdef"

/** 生成 [bytes] 字节的随机十六进制串（配对令牌用）。 */
fun randomHex(bytes: Int): String {
    val buffer = ByteArray(bytes)
    java.security.SecureRandom().nextBytes(buffer)
    val out = StringBuilder(bytes * 2)
    for (b in buffer) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

/** 服务端只认这一种图片名：64 位小写十六进制。 */
fun isSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

// ---------------------------------------------------------------- 反代与来源地址

/**
 * 判断来源地址可信与否，并据此决定要不要采信 `X-Forwarded-For`。
 *
 * 反代后面按 IP 限流有个经典坑：不认 XFF，所有人都是反代的地址，限流形同虚设；
 * 无脑认 XFF，攻击者自己加一个头就绕过去了。所以只对**可信来源**认这个头。
 */
class TrustedProxies(entries: List<String>) {

    private val exact = HashSet<String>()
    private val v4Networks = ArrayList<Pair<Int, Int>>() // (网络号, 掩码位数)

    init {
        for (raw in entries) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val slash = entry.indexOf('/')
            if (slash > 0) {
                val ip = parseIpv4(entry.substring(0, slash))
                val bits = entry.substring(slash + 1).toIntOrNull()
                if (ip != null && bits != null && bits in 0..32) {
                    val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
                    v4Networks += (ip and mask) to bits
                    continue
                }
                log("可信反代地址看不懂，忽略：$entry")
            } else {
                exact += normalizeIp(entry)
            }
        }
    }

    fun contains(ip: String): Boolean {
        val normalized = normalizeIp(ip)
        if (normalized in exact) return true
        val v4 = parseIpv4(normalized) ?: return false
        return v4Networks.any { (network, bits) ->
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            (v4 and mask) == network
        }
    }

    /** 真正拿来限流的地址：来源可信才用 XFF 里的第一个地址。 */
    fun clientIp(peer: String, forwardedFor: String?): String {
        if (forwardedFor.isNullOrBlank()) return normalizeIp(peer)
        if (!contains(peer)) return normalizeIp(peer)
        val first = forwardedFor.substringBefore(',').trim()
        return if (first.isEmpty()) normalizeIp(peer) else first
    }

    private fun normalizeIp(ip: String): String {
        var value = ip.trim()
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length - 1)
        // IPv4-mapped IPv6（::ffff:192.168.1.5）按 IPv4 处理
        val mapped = value.substringAfter("::ffff:", "")
        if (mapped.isNotEmpty()) value = mapped
        return value.lowercase()
    }

    private fun parseIpv4(ip: String): Int? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result = (result shl 8) or value
        }
        return result
    }
}

// ---------------------------------------------------------------- 限流

/**
 * 按分钟滑窗的简易限流（每台设备 / 每个 IP 一份计数）。家用规模，够用即可。
 */
class RateLimiter(private val perMinute: Int) {

    private class Window(var startMs: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        if (perMinute <= 0) return true
        if (windows.size > 512) prune(now)
        val window = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startMs >= 60_000) Window(now, 1)
            else existing.also { it.count += 1 }
        }!!
        return window.count <= perMinute
    }

    private fun prune(now: Long) {
        windows.entries.removeIf { now - it.value.startMs >= 60_000 }
    }
}

// ---------------------------------------------------------------- HTTP 读写

/** 读请求体，超过上限返回 null（调用方回 413）。最多多读 1 个字节来判断「超了」。 */
fun HttpExchange.readBodyCapped(maxBytes: Long): ByteArray? {
    val body = requestBody ?: return ByteArray(0)
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    body.use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) return null
        }
    }
    return out.toByteArray()
}

fun HttpExchange.sendJson(status: Int, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

fun HttpExchange.sendError(status: Int, error: String, message: String = "") {
    sendJson(status, SyncJson.json.encodeToString(ApiError(error, message)))
}

fun HttpExchange.sendBytes(status: Int, contentType: String, bytes: ByteArray) {
    responseHeaders.set("Content-Type", contentType)
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/** 取 `Authorization: Bearer xxx` 里的令牌，没有就是 null。 */
fun HttpExchange.bearerToken(): String? {
    val header = requestHeaders.getFirst("Authorization") ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().ifEmpty { null }
}

/** 取 query 参数（不处理重复键，取第一个）。 */
fun HttpExchange.queryParam(name: String): String? {
    val query = requestURI.rawQuery ?: return null
    return query.split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it[0] == name }
        ?.let { if (it.size > 1) java.net.URLDecoder.decode(it[1], "UTF-8") else "" }
}
