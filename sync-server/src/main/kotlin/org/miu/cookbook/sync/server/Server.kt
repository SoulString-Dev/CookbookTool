package org.miu.cookbook.sync.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.miu.cookbook.sync.model.BlobCheckRequest
import org.miu.cookbook.sync.model.BlobCheckResponse
import org.miu.cookbook.sync.model.HealthResponse
import org.miu.cookbook.sync.model.PairRequest
import org.miu.cookbook.sync.model.PushRequest
import org.miu.cookbook.sync.model.SyncJson
import org.miu.cookbook.sync.model.SyncProtocol
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 接口里主动抛出的错误，带 HTTP 状态码与给客户端看的错误码。 */
class HttpError(val status: Int, val code: String, message: String = "") : RuntimeException(message)

/**
 * HTTP 层。
 *
 * **为什么不用 Ktor**：这台机器拉不到任何 Maven 依赖（Ktor/Netty 都不在本地缓存里），
 * 家用 NAS 上跑的服务端也不需要多高级的框架。JDK 自带的 `com.sun.net.httpserver`
 * 加上手写的路由与 JSON，总共就这几百行，还少了几十兆依赖。
 */
class SyncServer(private val config: ServerConfig) : AutoCloseable {

    private val store = Store(config.dataDir)
    private val trustedProxies = TrustedProxies(config.trustedProxies)
    private val apiLimiter = RateLimiter(config.apiPerMinute)
    private val blobLimiter = RateLimiter(config.blobPerMinute)
    private val pairLimiter = RateLimiter(config.pairPerMinute)
    private val executor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "sync-http").apply { isDaemon = true }
    }
    private var http: HttpServer? = null
    private var gcThread: Thread? = null

    /** @return 实际监听端口（测试里传 0 就是随机端口） */
    fun start(bind: InetSocketAddress = InetSocketAddress(config.port)): Int {
        val server = HttpServer.create(bind, 64)
        server.createContext("/") { exchange -> handleSafely(exchange) }
        server.executor = executor
        server.start()
        http = server
        startGcLoop()
        return server.address.port
    }

    override fun close() {
        http?.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        store.close()
    }

    /** 供测试与管理用：直接拿到数据层。 */
    fun store(): Store = store

    /** 当前有效的一次性配对码（固定码模式下返回配置里的那个）。 */
    fun currentPairCode(): String =
        config.pairCode.ifEmpty { store.currentPairCode() ?: store.newPairCode() }

    fun pairCodeFile(): File = File(config.dataDir, "pair-code.txt")

    // ------------------------------------------------------------ 路由

    private fun handleSafely(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (e: HttpError) {
            runCatching { exchange.sendError(e.status, e.code, e.message.orEmpty()) }
        } catch (t: Throwable) {
            log("处理 ${exchange.requestMethod} ${exchange.requestURI} 出错：$t")
            runCatching { exchange.sendError(500, "internal", t.message.orEmpty()) }
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path.let { if (it.length > 1) it.trimEnd('/') else it }
        val method = exchange.requestMethod.uppercase()
        if (config.requireHttps && path != SyncProtocol.PATH_HEALTH && !isHttps(exchange)) {
            throw HttpError(400, "https_required", "这个服务端只接受 HTTPS，请从域名访问")
        }
        when {
            path == SyncProtocol.PATH_HEALTH && method == "GET" -> health(exchange)
            path == SyncProtocol.PATH_PAIR && method == "POST" -> pair(exchange)
            path == SyncProtocol.PATH_SYNC && method == "GET" -> pull(exchange)
            path == SyncProtocol.PATH_SYNC && method == "POST" -> push(exchange)
            path == SyncProtocol.PATH_BLOB_CHECK && method == "POST" -> blobCheck(exchange)
            path.startsWith(SyncProtocol.PATH_BLOB_PREFIX) && method == "PUT" -> blobPut(exchange, path)
            path.startsWith(SyncProtocol.PATH_BLOB_PREFIX) && method == "GET" -> blobGet(exchange, path)
            else -> throw HttpError(404, "not_found", "没有这个接口：$method $path")
        }
    }

    /**
     * 这次请求算不算 HTTPS。服务端自己只说明文，所以只能看可信反代给的 `X-Forwarded-Proto`；
     * 来源不可信时直接当明文——伪造这个头的请求本来就会被拒。
     */
    private fun isHttps(exchange: HttpExchange): Boolean {
        val peer = exchange.remoteAddress.address?.hostAddress.orEmpty()
        if (!trustedProxies.contains(peer)) return false
        return exchange.requestHeaders.getFirst("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true
    }

    // ------------------------------------------------------------ 各接口

    private fun health(exchange: HttpExchange) {
        val body = HealthResponse(
            ok = true,
            version = config.version,
            serverRev = store.rev(),
            recipes = store.recipeCount(),
            serverTime = System.currentTimeMillis(),
        )
        exchange.sendJson(200, SyncJson.json.encodeToString(body))
    }

    private fun pair(exchange: HttpExchange) {
        val peer = exchange.remoteAddress.address?.hostAddress.orEmpty()
        val clientIp = trustedProxies.clientIp(peer, exchange.requestHeaders.getFirst("X-Forwarded-For"))
        if (!pairLimiter.allow(clientIp)) throw HttpError(429, "rate_limited", "配对请求太频繁，稍后再试")

        val body = exchange.readBodyCapped(64 * 1024) ?: throw HttpError(413, "too_large", "请求体太大")
        val request = decode<PairRequest>(body)
        if (request.code.isBlank()) throw HttpError(400, "bad_request", "配对码不能为空")

        val response = store.pair(request.code, request.deviceName, config.pairCode)
            ?: throw HttpError(403, "bad_code", "配对码不对或已过期")
        // 一次性配对码用过即废，立刻补一个新的写进文件，管理员 cat 一下就能看到
        if (config.pairCode.isEmpty()) {
            val next = store.newPairCode()
            runCatching { pairCodeFile().writeText(next + "\n") }
            log("配对码已更新：$next（也写在 ${pairCodeFile().path}）")
        }
        exchange.sendJson(200, SyncJson.json.encodeToString(response))
    }

    private fun pull(exchange: HttpExchange) {
        val deviceId = requireDevice(exchange)
        requireQuota(apiLimiter, deviceId)
        val since = exchange.queryParam("since")?.toLongOrNull() ?: 0L
        val limit = (exchange.queryParam("limit")?.toIntOrNull() ?: SyncProtocol.MAX_PULL_LIMIT)
            .coerceIn(1, SyncProtocol.MAX_PULL_LIMIT)
        exchange.sendJson(200, SyncJson.json.encodeToString(store.pull(since.coerceAtLeast(0), limit)))
    }

    private fun push(exchange: HttpExchange) {
        val deviceId = requireDevice(exchange)
        requireQuota(apiLimiter, deviceId)
        val body = exchange.readBodyCapped(SyncProtocol.MAX_JSON_BYTES)
            ?: throw HttpError(413, "too_large", "推送的内容超过 ${SyncProtocol.MAX_JSON_BYTES / 1024 / 1024} MB，请分批")
        val request = decode<PushRequest>(body)
        if (request.deviceId.isNotEmpty() && request.deviceId != deviceId) {
            throw HttpError(403, "device_mismatch", "令牌与 deviceId 对不上")
        }
        if (request.baseRev < 0) throw HttpError(400, "bad_request", "baseRev 不能是负数")
        if (request.upserts.size > SyncProtocol.MAX_PUSH_ITEMS || request.deletes.size > SyncProtocol.MAX_PUSH_ITEMS) {
            throw HttpError(413, "too_many_items", "一次最多推 ${SyncProtocol.MAX_PUSH_ITEMS} 条，请分批")
        }
        request.upserts.forEach { upsert ->
            if (upsert.uuid.isBlank()) throw HttpError(400, "bad_request", "uuid 不能为空")
            if (upsert.updatedAt <= 0) throw HttpError(400, "bad_request", "updatedAt 必须是正数")
            upsert.payload.blobHashes().forEach {
                if (!isSha256(it)) throw HttpError(400, "bad_request", "图片哈希不是 sha256：$it")
            }
        }
        request.deletes.forEach {
            if (it.uuid.isBlank()) throw HttpError(400, "bad_request", "uuid 不能为空")
            if (it.deletedAt <= 0) throw HttpError(400, "bad_request", "deletedAt 必须是正数")
        }
        val response = store.push(deviceId, request)
        if (response.conflicts.isNotEmpty()) {
            log("设备 $deviceId 推送出现 ${response.conflicts.size} 条冲突：${response.conflicts.joinToString { it.uuid }}")
        }
        exchange.sendJson(200, SyncJson.json.encodeToString(response))
    }

    private fun blobCheck(exchange: HttpExchange) {
        val deviceId = requireDevice(exchange)
        requireQuota(apiLimiter, deviceId)
        val body = exchange.readBodyCapped(512 * 1024) ?: throw HttpError(413, "too_large", "一次问太多哈希了")
        val request = decode<BlobCheckRequest>(body)
        val missing = request.sha256.filter { !store.blobs.exists(it) }
        exchange.sendJson(200, SyncJson.json.encodeToString(BlobCheckResponse(missing)))
    }

    private fun blobPut(exchange: HttpExchange, path: String) {
        val deviceId = requireDevice(exchange)
        requireQuota(blobLimiter, deviceId)
        val sha256 = path.removePrefix(SyncProtocol.PATH_BLOB_PREFIX)
        if (!isSha256(sha256)) throw HttpError(400, "bad_request", "文件名必须是小写十六进制的 sha256")
        val body = exchange.readBodyCapped(SyncProtocol.MAX_BLOB_BYTES)
            ?: throw HttpError(413, "too_large", "单张图片超过 ${SyncProtocol.MAX_BLOB_BYTES / 1024 / 1024} MB")
        if (body.isEmpty()) throw HttpError(400, "bad_request", "图片是空的")
        if (!store.blobs.put(sha256, body)) {
            throw HttpError(400, "hash_mismatch", "内容与文件名里的 sha256 不一致")
        }
        store.rememberBlob(sha256, body.size)
        exchange.sendJson(204, "")
    }

    private fun blobGet(exchange: HttpExchange, path: String) {
        val deviceId = requireDevice(exchange)
        requireQuota(blobLimiter, deviceId)
        val sha256 = path.removePrefix(SyncProtocol.PATH_BLOB_PREFIX)
        if (!isSha256(sha256)) throw HttpError(400, "bad_request", "文件名必须是小写十六进制的 sha256")
        val bytes = store.blobs.read(sha256) ?: throw HttpError(404, "not_found", "服务端没有这张图")
        exchange.sendBytes(200, "image/jpeg", bytes)
    }

    // ------------------------------------------------------------ 小工具

    private fun requireDevice(exchange: HttpExchange): String {
        val token = exchange.bearerToken() ?: throw HttpError(401, "unauthorized", "缺少 Bearer 令牌")
        return store.authenticate(token) ?: throw HttpError(401, "unauthorized", "令牌无效或设备已吊销")
    }

    private fun requireQuota(limiter: RateLimiter, key: String) {
        if (!limiter.allow(key)) throw HttpError(429, "rate_limited", "请求太频繁")
    }

    private inline fun <reified T> decode(body: ByteArray): T =
        try {
            SyncJson.json.decodeFromString(body.decodeToString())
        } catch (e: Exception) {
            throw HttpError(400, "bad_json", e.message.orEmpty())
        }

    /** 每小时扫一遍：墓碑到期物理删，孤儿图片（超过 24 小时没人引用）也清掉。 */
    private fun startGcLoop() {
        gcThread = thread(isDaemon = true, name = "sync-gc") {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val (tombstones, blobs) = store.gc(config.tombstoneDays)
                    if (tombstones > 0 || blobs > 0) log("清理：墓碑 $tombstones 条，孤儿图片 $blobs 张")
                } catch (t: Throwable) {
                    log("清理出错：$t")
                }
                try {
                    Thread.sleep(TimeUnit.HOURS.toMillis(1))
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }
}
