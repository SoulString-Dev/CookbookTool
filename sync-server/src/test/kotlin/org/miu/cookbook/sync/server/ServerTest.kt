package org.miu.cookbook.sync.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.miu.cookbook.sync.model.ApiError
import org.miu.cookbook.sync.model.BlobCheckRequest
import org.miu.cookbook.sync.model.BlobCheckResponse
import org.miu.cookbook.sync.model.HealthResponse
import org.miu.cookbook.sync.model.PairRequest
import org.miu.cookbook.sync.model.PairResponse
import org.miu.cookbook.sync.model.PullResponse
import org.miu.cookbook.sync.model.PushDelete
import org.miu.cookbook.sync.model.PushRequest
import org.miu.cookbook.sync.model.PushResponse
import org.miu.cookbook.sync.model.PushUpsert
import org.miu.cookbook.sync.model.SyncCategoryRef
import org.miu.cookbook.sync.model.SyncJson
import org.miu.cookbook.sync.model.SyncProtocol
import org.miu.cookbook.sync.model.SyncRecipePayload
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 服务端端到端测试：真起一个进程内 HTTP 服务，用真 HTTP 请求打它。
 *
 * 不用假服务端也不用 Mock——这块代码全是「协议 + 数据库」的活儿，
 * 打桩打不出真问题（分页水位、冲突发号、哈希校验这些恰恰是最容易写错的地方）。
 */
class ServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SyncServer
    private lateinit var base: String
    private val http = HttpClient.newHttpClient()

    private class Res(val status: Int, val bytes: ByteArray) {
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    @Before
    fun setUp() {
        server = newServer()
        base = "http://127.0.0.1:${server.start()}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun newServer(apiPerMinute: Int = 0): SyncServer = SyncServer(
        ServerConfig(
            port = 0,
            dataDir = tmp.newFolder(),
            pairCode = FIXED_CODE,
            // 测试里默认关掉限流，免得互相影响；要测限流的用例自己开
            apiPerMinute = apiPerMinute,
            blobPerMinute = 0,
            pairPerMinute = 0,
        ),
    )

    // ------------------------------------------------------------ 用例

    @Test
    fun `health 不需要令牌`() {
        val res = call("GET", SyncProtocol.PATH_HEALTH)
        assertEquals(200, res.status)
        val health = decode<HealthResponse>(res.text)
        assertTrue(health.ok)
        assertEquals(0L, health.serverRev)
        assertEquals(0L, health.recipes)
    }

    @Test
    fun `没有令牌的同步请求会被拒`() {
        val res = call("GET", SyncProtocol.PATH_SYNC)
        assertEquals(401, res.status)
        assertEquals("unauthorized", decode<ApiError>(res.text).error)
        assertEquals(401, call("POST", SyncProtocol.PATH_SYNC, json = "{}").status)
    }

    @Test
    fun `配对码换令牌，错的换不到`() {
        val paired = pair("甲的手机")
        assertTrue(paired.token.isNotBlank())
        assertTrue(paired.deviceId.isNotBlank())
        assertEquals(200, call("GET", SyncProtocol.PATH_SYNC, token = paired.token).status)

        val bad = call("POST", SyncProtocol.PATH_PAIR, json = json(PairRequest("000000", "坏设备")))
        assertEquals(403, bad.status)
        assertEquals("bad_code", decode<ApiError>(bad.text).error)
    }

    @Test
    fun `一台推上去的记录另一台能拉到`() {
        val a = pair("甲")
        val b = pair("乙")
        val payload = SyncRecipePayload(
            title = "红烧肉",
            createdAt = 1000,
            updatedAt = 1000,
            categories = listOf(SyncCategoryRef("c-1", "家常菜")),
        )
        val pushed = push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, payload))))
        assertEquals(1, pushed.records.size)
        assertEquals(1L, pushed.records[0].rev)
        assertTrue(pushed.conflicts.isEmpty())

        val pulled = pull(b.token, 0)
        assertEquals(1, pulled.recipes.size)
        assertEquals("r-1", pulled.recipes[0].uuid)
        assertEquals("红烧肉", pulled.recipes[0].payload?.title)
        assertEquals("c-1", pulled.recipes[0].payload?.categories?.first()?.uuid)
        assertEquals(1L, pulled.nextSince)
        assertFalse(pulled.hasMore)
    }

    @Test
    fun `分页拉取途中有人写入也不会漏记录`() {
        val a = pair("甲")
        val b = pair("乙")
        repeat(5) { index ->
            push(
                a.token,
                PushRequest(a.deviceId, 0, listOf(PushUpsert("r-$index", 1000L + index, recipe("菜 $index", 1000L + index)))),
            )
        }
        val first = pull(b.token, 0, limit = 2)
        assertEquals(listOf("r-0", "r-1"), first.recipes.map { it.uuid })
        // 关键：水位推到「本次最后一条的 rev」，不是服务端全局水位
        assertEquals(2L, first.nextSince)
        assertTrue(first.hasMore)

        // 分页途中又来一条，服务端水位涨到 6
        push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-5", 2000, recipe("新菜", 2000)))))

        val seen = first.recipes.map { it.uuid }.toMutableList()
        var since = first.nextSince
        while (true) {
            val page = pull(b.token, since, limit = 2)
            seen += page.recipes.map { it.uuid }
            since = page.nextSince
            if (!page.hasMore) break
        }
        assertEquals(setOf("r-0", "r-1", "r-2", "r-3", "r-4", "r-5"), seen.toSet())
        assertEquals(6, seen.size)
    }

    @Test
    fun `客户端水位比服务端高时从头拉`() {
        val a = pair("甲")
        push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("菜", 1000)))))
        // 服务端换了库 / 回滚之后，客户端水位虚高，不能让它从此什么都拉不到
        val pulled = pull(a.token, 999)
        assertEquals(1, pulled.recipes.size)
        assertEquals(1L, pulled.nextSince)
    }

    @Test
    fun `重推同一条不再发新号`() {
        val a = pair("甲")
        val request = PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("红烧肉", 1000))))
        val first = push(a.token, request)
        val revAfterFirst = server.store().rev()
        val again = push(a.token, request)
        assertEquals(first.records[0].rev, again.records[0].rev)
        assertEquals(revAfterFirst, server.store().rev())
    }

    @Test
    fun `冲突时新的赢，旧的留成冲突副本`() {
        val a = pair("甲")
        val b = pair("乙")
        push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("原来的", 1000)))))

        // 乙没拉到过（baseRev = 0），拿着更旧的版本推 → 服务端赢，乙的变成副本
        val older = push(b.token, PushRequest(b.deviceId, 0, listOf(PushUpsert("r-1", 900, recipe("乙的旧版", 900)))))
        assertEquals(1, older.conflicts.size)
        assertEquals("server", older.conflicts[0].winner)
        val firstCopy = older.conflicts[0].copyUuid
        assertTrue(firstCopy.isNotBlank())
        assertEquals("乙的旧版（冲突副本）", older.records.first { it.uuid == firstCopy }.payload?.title)
        assertEquals("原来的", older.records.first { it.uuid == "r-1" }.payload?.title)

        // 反过来：乙这次更晚 → 乙赢，服务端原来的变成副本
        val newer = push(b.token, PushRequest(b.deviceId, 0, listOf(PushUpsert("r-1", 1100, recipe("乙的新版", 1100)))))
        assertEquals("client", newer.conflicts[0].winner)
        assertEquals("乙的新版", newer.records.first { it.uuid == "r-1" }.payload?.title)
        assertEquals(
            "原来的（冲突副本）",
            newer.records.first { it.uuid == newer.conflicts[0].copyUuid }.payload?.title,
        )
    }

    @Test
    fun `同一批推送重发不会造出第二个冲突副本`() {
        val a = pair("甲")
        val b = pair("乙")
        push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("原来的", 1000)))))

        // 乙拿着更旧的版本推（服务端赢），随后**原样重发同一批**（模拟响应丢了之后的重试）
        val request = PushRequest(b.deviceId, 0, listOf(PushUpsert("r-1", 900, recipe("乙的旧版", 900))))
        val first = push(b.token, request)
        val second = push(b.token, request)

        assertEquals(first.conflicts[0].copyUuid, second.conflicts[0].copyUuid)

        // 服务端总共只该有一份带后缀的副本
        val all = pull(a.token, 0).recipes
        assertEquals(
            listOf("乙的旧版（冲突副本）"),
            all.filter { it.payload?.title?.endsWith("（冲突副本）") == true }.map { it.payload?.title },
        )
    }

    @Test
    fun `删除会变成墓碑，别的设备拉得到`() {
        val a = pair("甲")
        val b = pair("乙")
        push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("要删的", 1000)))))
        val res = push(a.token, PushRequest(a.deviceId, 1, emptyList(), listOf(PushDelete("r-1", 2000))))
        assertTrue(res.records.first { it.uuid == "r-1" }.deleted)

        val pulled = pull(b.token, 0)
        val tombstone = pulled.recipes.last()
        assertEquals("r-1", tombstone.uuid)
        assertTrue(tombstone.deleted)
        assertNull(tombstone.payload)
    }

    @Test
    fun `删除不产生冲突副本`() {
        val a = pair("甲")
        val b = pair("乙")
        push(a.token, PushRequest(a.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("在的", 1000)))))

        // 乙没拉到就删，但时间戳比服务端的旧 → 删除作废，而且不留副本
        val rejected = push(b.token, PushRequest(b.deviceId, 0, emptyList(), listOf(PushDelete("r-1", 500))))
        assertEquals(1, rejected.conflicts.size)
        assertEquals("", rejected.conflicts[0].copyUuid)
        assertFalse(rejected.records.first { it.uuid == "r-1" }.deleted)
        assertEquals(1L, server.store().recipeCount())

        // 时间戳更新才删得掉
        val accepted = push(b.token, PushRequest(b.deviceId, 0, emptyList(), listOf(PushDelete("r-1", 3000))))
        assertTrue(accepted.records.first { it.uuid == "r-1" }.deleted)
        assertEquals(0L, server.store().recipeCount())
    }

    @Test
    fun `推送里带别人的 deviceId 会被拒`() {
        val a = pair("甲")
        val b = pair("乙")
        val res = call(
            "POST",
            SyncProtocol.PATH_SYNC,
            token = a.token,
            json = json(PushRequest(b.deviceId, 0, listOf(PushUpsert("r-1", 1000, recipe("菜", 1000))))),
        )
        assertEquals(403, res.status)
        assertEquals("device_mismatch", decode<ApiError>(res.text).error)
    }

    @Test
    fun `图片按内容哈希存取，哈希对不上不收`() {
        val a = pair("甲")
        val bytes = "假装这是一张图".toByteArray()
        val sha = sha256Hex(bytes)

        assertEquals(204, call("PUT", SyncProtocol.blob(sha), token = a.token, bytes = bytes).status)
        val got = call("GET", SyncProtocol.blob(sha), token = a.token)
        assertEquals(200, got.status)
        assertArrayEquals(bytes, got.bytes)

        val wrong = call("PUT", SyncProtocol.blob(sha256Hex("别的内容".toByteArray())), token = a.token, bytes = bytes)
        assertEquals(400, wrong.status)
        assertEquals("hash_mismatch", decode<ApiError>(wrong.text).error)

        val unknown = "0".repeat(64)
        val checked = call(
            "POST",
            SyncProtocol.PATH_BLOB_CHECK,
            token = a.token,
            json = json(BlobCheckRequest(listOf(sha, unknown))),
        )
        assertEquals(listOf(unknown), decode<BlobCheckResponse>(checked.text).missing)
        assertEquals(404, call("GET", SyncProtocol.blob(unknown), token = a.token).status)
    }

    @Test
    fun `同步接口按设备限流`() {
        val limited = newServer(apiPerMinute = 2)
        val url = "http://127.0.0.1:${limited.start()}"
        try {
            val token = pair("被限流的", at = url).token
            assertEquals(200, call("GET", SyncProtocol.PATH_SYNC, token = token, at = url).status)
            assertEquals(200, call("GET", SyncProtocol.PATH_SYNC, token = token, at = url).status)
            val third = call("GET", SyncProtocol.PATH_SYNC, token = token, at = url)
            assertEquals(429, third.status)
            assertEquals("rate_limited", decode<ApiError>(third.text).error)
        } finally {
            limited.close()
        }
    }

    @Test
    fun `只对可信来源认 X-Forwarded-For`() {
        val proxies = TrustedProxies(listOf("127.0.0.1", "10.0.0.0/8", "172.16.0.0/12"))
        assertTrue(proxies.contains("10.1.2.3"))
        assertTrue(proxies.contains("::ffff:10.1.2.3"))
        assertTrue(proxies.contains("172.20.0.9"))
        assertFalse(proxies.contains("11.0.0.1"))

        // 可信反代转来的请求：按 XFF 里第一个地址限流
        assertEquals("1.2.3.4", proxies.clientIp("10.1.2.3", "1.2.3.4, 5.6.7.8"))
        // 不是可信来源：伪造 XFF 也没用，按来源地址算
        assertEquals("203.0.113.9", proxies.clientIp("203.0.113.9", "1.2.3.4"))
        assertEquals("10.1.2.3", proxies.clientIp("10.1.2.3", null))
    }

    @Test
    fun `墓碑到期清理，孤儿图片过了宽限期才删`() {
        val store = server.store()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        store.push("dev-1", PushRequest("dev-1", 0, listOf(PushUpsert("r-1", now, recipe("要被清的", now)))), now)
        store.push("dev-1", PushRequest("dev-1", 1, emptyList(), listOf(PushDelete("r-1", now))), now)

        // 还没到期：留着
        assertEquals(0 to 0, store.gc(30, now + 29 * day))
        // 到期了：墓碑物理删
        assertEquals(1 to 0, store.gc(30, now + 31 * day))

        // 图片：刚传上来的孤儿在宽限期内不动（有「先传图再推记录的」空窗期）
        val bytes = "一张孤儿图".toByteArray()
        val sha = sha256Hex(bytes)
        store.blobs.put(sha, bytes)
        store.rememberBlob(sha, bytes.size, now)
        assertEquals(0 to 0, store.gc(30, now + 3_600_000L))
        assertEquals(0 to 1, store.gc(30, now + 25 * 3_600_000L))
        assertFalse(store.blobs.exists(sha))
    }

    // ------------------------------------------------------------ 小工具

    private fun call(
        method: String,
        path: String,
        token: String? = null,
        json: String? = null,
        bytes: ByteArray? = null,
        at: String = base,
    ): Res {
        val builder = HttpRequest.newBuilder(URI.create(at + path))
        val publisher = when {
            bytes != null -> HttpRequest.BodyPublishers.ofByteArray(bytes)
            json != null -> HttpRequest.BodyPublishers.ofString(json)
            else -> HttpRequest.BodyPublishers.noBody()
        }
        builder.method(method, publisher)
        if (json != null || bytes != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return Res(response.statusCode(), response.body())
    }

    private fun pair(name: String, at: String = base): PairResponse {
        val res = call(
            "POST",
            SyncProtocol.PATH_PAIR,
            json = json(PairRequest(FIXED_CODE, name)),
            at = at,
        )
        assertEquals("配对失败：${res.text}", 200, res.status)
        return decode(res.text)
    }

    private fun push(token: String, request: PushRequest, at: String = base): PushResponse {
        val res = call("POST", SyncProtocol.PATH_SYNC, token = token, json = json(request), at = at)
        assertEquals("推送失败：${res.text}", 200, res.status)
        return decode(res.text)
    }

    private fun pull(
        token: String,
        since: Long,
        limit: Int = SyncProtocol.MAX_PULL_LIMIT,
        at: String = base,
    ): PullResponse {
        val res = call("GET", "${SyncProtocol.PATH_SYNC}?since=$since&limit=$limit", token = token, at = at)
        assertEquals("拉取失败：${res.text}", 200, res.status)
        return decode(res.text)
    }

    private fun recipe(title: String, updatedAt: Long): SyncRecipePayload =
        SyncRecipePayload(title = title, createdAt = updatedAt, updatedAt = updatedAt)

    private inline fun <reified T> decode(text: String): T = SyncJson.json.decodeFromString(text)

    private fun json(value: Any): String = when (value) {
        is PairRequest -> SyncJson.json.encodeToString(value)
        is PushRequest -> SyncJson.json.encodeToString(value)
        is BlobCheckRequest -> SyncJson.json.encodeToString(value)
        else -> error("没准备这个类型的编码：${value::class}")
    }

    private companion object {
        const val FIXED_CODE = "123456"
    }
}
