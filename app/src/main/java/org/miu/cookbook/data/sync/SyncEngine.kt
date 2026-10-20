package org.miu.cookbook.data.sync

import android.os.NetworkOnMainThreadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.SyncStateEntity
import org.miu.cookbook.sync.model.PairResponse
import org.miu.cookbook.sync.model.PushDelete
import org.miu.cookbook.sync.model.PushRequest
import org.miu.cookbook.sync.model.PushUpsert
import org.miu.cookbook.sync.model.SyncProtocol
import org.miu.cookbook.sync.model.SyncRecord
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 一次同步的账：拉了几条、推了几条、冒出几份冲突副本、水位推到哪。 */
data class SyncReport(
    val pulled: Int,
    val pushed: Int,
    val conflicts: Int,
    val lastRev: Long,
)

/**
 * 带人话的失败：[message] 就是 [SyncEngine] 里 describe() 翻译过的那句，界面直接显示即可。
 *
 * 为什么不用原始异常：像 `NetworkOnMainThreadException` 这种 message 是 null，
 * 界面只能退化成「未知原因」，等于没说。
 */
class SyncFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 同步进度，给设置页显示。（真正的「上次同步时间」在库里，进程重启也还在。） */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val report: SyncReport, val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * 同步引擎：**只做编排，不碰数据库细节**（那些在 [SyncStore]）。
 *
 * 一轮同步的顺序是固定的「先拉后推」，理由：
 * 1. 先拉才知道服务端上每条记录现在是什么版本，推送时的冲突判断才有意义；
 * 2. 推之前把别人的改动落地，能让「两个人都改了同一道菜」只产生一份副本，而不是各推各的来回打架。
 *
 * 水位（`lastRev`）的几条铁律：
 * - 只认拉取返回的 `nextSince`，**绝不拿推送响应里的 `serverRev` 去推水位**——推送期间别人可能又推了
 *   一批，直接跳过去就永久漏掉了；
 * - 这一页里有「本地还有没推上去的改动」而被跳过的记录时，水位要**退到那条之前**，
 *   否则下一次拉取就再也看不到它了（这是最容易写错的一处）；
 * - 配对成功时水位必须归零：新设备第一次要把服务端已有的记录全拉一遍。
 */
class SyncEngine(
    database: AppDatabase,
    private val repository: CookbookRepository,
    baseDir: File,
    private val credentials: SyncCredentials,
    /** 后台同步活在这里：编辑器退出后的那一发不能跟着界面的 lifecycle 一起死。 */
    private val scope: CoroutineScope,
) {

    private val store = SyncStore(database, baseDir)
    private val mutex = Mutex()
    private val launchScope = scope

    /** 「有点事要同步」的信号，合并式：连点十下也只跑一轮。 */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val paired: Boolean get() = credentials.paired

    /**
     * 配对：换令牌、存地址、**把游标归零**。
     *
     * 归零这一步很容易漏：配对时服务端会回一个 `serverRev`，顺手拿它当水位的话，
     * 新设备第一次同步会把服务端上已有的记录全部跳过——看起来「同步成功」，其实一张菜谱都没下来。
     */
    suspend fun pair(
        primaryUrl: String,
        localUrl: String,
        code: String,
        deviceName: String,
    ): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        credentials.primaryUrl = primaryUrl.trim().trimEnd('/')
        credentials.localUrl = localUrl.trim().trimEnd('/')

        val candidates = credentials.candidateUrls(credentials.primaryUrl)
        if (candidates.isEmpty()) throw IllegalStateException("还没填服务端地址")

        var response: PairResponse? = null
        var usedUrl = ""
        var lastError: Throwable? = null
        for ((index, url) in candidates.withIndex()) {
            val api = SyncApi(url, connectTimeoutMs = timeoutFor(index, candidates.lastIndex))
            try {
                response = api.pair(code.trim(), deviceName.trim())
                usedUrl = url
                break
            } catch (error: SyncHttpException) {
                // 服务端答话了：配对码不对就是配对码不对，换个地址也一样
                throw error
            } catch (error: IOException) {
                lastError = error
            }
        }
        val paired = response ?: throw (lastError ?: IOException("连不上服务端"))

        credentials.token = paired.token
        credentials.deviceName = deviceName.trim()
        credentials.lastGoodUrl = usedUrl

        val current = repository.currentSyncState() ?: SyncStateEntity()
        repository.saveSyncState(
            current.copy(
                serverUrl = usedUrl,
                // 服务端发的设备号，不是本机自己编的那个：令牌跟它绑定，推的时候必须带同一个
                deviceId = paired.deviceId,
                lastRev = 0,
                lastSyncAt = 0,
                lastError = "",
            ),
        )
        _status.value = SyncStatus.Idle
    }.onFailure { _status.value = SyncStatus.Failed(describe(it)) }.humanFailure() }

    /** 断开设备：清掉令牌与地址。本地数据和脏标记都留着（以后再配一个服务端还能整体推上去）。 */
    suspend fun disconnect() = mutex.withLock {
        credentials.clear()
        repository.currentSyncState()?.let {
            repository.saveSyncState(it.copy(serverUrl = "", lastRev = 0, lastError = ""))
        }
        _status.value = SyncStatus.Idle
    }

    /**
     * 同步一次。同一时刻只许跑一轮（[mutex]）：两轮并行会让水位和脏标记互相踩。
     *
     * 失败不抛给界面，包在 [Result] 里：调用方要的是一句能显示的话，不是一个崩溃。
     */
    suspend fun syncNow(): Result<SyncReport> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                syncUnlocked()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 兜底：`require` 失败、服务端回了 200 却不是 JSON、Room 抛 SQLiteException……
                // 这些都不该冲出 Result 契约（状态会永远停在 Running，后台 worker 里还会崩），
                // 更不该让界面看到一句「未知原因」。
                fail(error)
            }
        }
    }

    /**
     * [syncNow] 的本体。
     *
     * 单独抽出来是因为**网络调用必须离开界面线程**：`HttpURLConnection` 是阻塞的，在 Android 的
     * 主线程上一发请求就会抛 `NetworkOnMainThreadException`——它的 message 是 null，
     * 界面只能显示「未知原因」，排查起来毫无线索（1.1.0 的配对失败就是这么来的）。
     */
    private suspend fun syncUnlocked(): Result<SyncReport> {
        val state = repository.currentSyncState()
        if (!credentials.paired || state == null) {
            return Result.failure(IllegalStateException("还没配对"))
        }

        val candidates = credentials.candidateUrls(state.serverUrl)
        if (candidates.isEmpty()) return fail(IllegalStateException("还没填服务端地址"))

        _status.value = SyncStatus.Running
        var lastError: Throwable? = null
        for ((index, url) in candidates.withIndex()) {
            val api = SyncApi(url, credentials.token, connectTimeoutMs = timeoutFor(index, candidates.lastIndex))
            try {
                val report = syncOnce(api, state)
                credentials.lastGoodUrl = url
                _status.value = SyncStatus.Done(report, System.currentTimeMillis())
                return Result.success(report)
            } catch (error: SyncHttpException) {
                // 服务端答话了：地址是对的，换一个地址也没用（电解不开就是解不开）
                return fail(error)
            } catch (error: IOException) {
                // 网络层失败（不在家、地址写错、服务端没起）：换下一个地址接着试
                lastError = error
            }
        }
        return fail(lastError ?: IOException("连不上服务端"), tried = candidates.size)
    }

    /**
     * 编辑完之后来一发：**把短时间内的多次调用合并成一次同步**。
     *
     * 不直接调 [syncNow] 的原因很实在：在原料/正文编辑器里来回按返回，会连着一串「退出编辑」，
     * 每次都真发一轮请求既浪费又容易自己跟自己打架。等两秒，安静下来再跑。
     */
    fun requestSync() {
        if (!paired) return
        if (worker?.isActive != true) {
            worker = scope.launch {
                for (ignored in requests) {
                    delay(DEBOUNCE_MILLIS)
                    // 等待期间又来的请求，含义和这一条一样，吞掉就行
                    while (requests.tryReceive().isSuccess) {
                        // 合并
                    }
                    syncNow()
                }
            }
        }
        requests.trySend(Unit)
    }

    private suspend fun fail(error: Throwable, tried: Int = 0): Result<SyncReport> {
        val base = describe(error)
        val message = if (tried > 1) "$base（$tried 个地址都试过了）" else base
        _status.value = SyncStatus.Failed(message)
        runCatching {
            repository.currentSyncState()?.let { repository.saveSyncState(it.copy(lastError = message)) }
        }
        return Result.failure(SyncFailure(message, error))
    }

    /** 把失败换成「带人话 message」的异常，界面就不必再自己猜原因。 */
    private fun <T> Result<T>.humanFailure(): Result<T> =
        exceptionOrNull()?.let { Result.failure(SyncFailure(describe(it), it)) } ?: this

    /** 前面几个候选地址用短超时探路，最后一个（通常是公网）才舍得等。 */
    private fun timeoutFor(index: Int, lastIndex: Int): Int =
        if (index < lastIndex) PROBE_TIMEOUT_MS else NORMAL_TIMEOUT_MS

    private suspend fun syncOnce(api: SyncApi, state: SyncStateEntity): SyncReport {
        // 正常情况用配对时服务端给的设备号；老数据没有的话回退到本机标识
        val deviceId = state.deviceId.ifBlank { repository.deviceId() }

        // 存量图片先补哈希：不补的话它们没有内容地址，等于永远不上传
        store.backfillHashes()

        var (since, pulled) = pullAll(api, state.lastRev)

        var pushed = 0
        var conflicts = 0
        val pushedUuids = mutableSetOf<String>()
        store.dirtyRecipes().chunked(SyncProtocol.MAX_PUSH_ITEMS).forEach { batch ->
            val files = mutableMapOf<String, File>()
            val upserts = mutableListOf<PushUpsert>()
            val deletes = mutableListOf<PushDelete>()
            batch.forEach { recipe ->
                if (recipe.deletedAt != 0L) {
                    deletes += store.deleteOf(recipe)
                } else {
                    store.buildUpsert(recipe, files)?.let { upserts += it }
                }
            }
            if (upserts.isEmpty() && deletes.isEmpty()) return@forEach

            uploadBlobs(api, files)
            val response = api.push(
                PushRequest(
                    deviceId = deviceId,
                    // 「我上次看到的水位」：服务端拿它判断这条记录我到底见过没有
                    baseRev = since,
                    upserts = upserts,
                    deletes = deletes,
                ),
            )
            // 推送期间用户可能又改了这道菜，把这次推上去的 updatedAt 带上，供落地时判断要不要清脏
            store.applyPush(response.records, upserts.associate { it.uuid to it.updatedAt })
            pushed += upserts.size + deletes.size
            conflicts += response.conflicts.size
            pushedUuids += batch.map { it.uuid }
        }

        // 推完再拉一轮，把水位推到服务端最新。
        // 这一轮是必须的：光靠「推之前的水位」推进不了，下次同步会把自己刚推上去的记录
        // 当成别人的改动拉下来，然后因为「本地更脏」跳过、水位退回去，越同步越乱。
        val (afterPush, extraPulled) = pullAll(api, since, ignore = pushedUuids)
        since = afterPush
        pulled += extraPulled

        val latest = repository.currentSyncState() ?: state
        repository.saveSyncState(
            latest.copy(
                // 记下这次真正连上的地址：设置页显示它，下次也先试它
                serverUrl = api.url,
                lastRev = since,
                lastSyncAt = System.currentTimeMillis(),
                lastError = "",
            ),
        )

        // 收尾：清过期墓碑、回收没人引用的图片文件。失败不影响这一轮的账
        // （清理是维护动作，不该让一次成功的同步变成失败）。
        runCatching { store.cleanupOrphans() }

        return SyncReport(pulled = pulled, pushed = pushed, conflicts = conflicts, lastRev = since)
    }

    /**
     * 从 [from] 一直拉到没有下一页，返回（推进后的水位, 新拉到的条数）。
     *
     * [ignore] 里的 uuid 是「这一轮自己刚推上去的」：服务端会把它们作为新记录回给我们，
     * 落地是幂等的空操作，但算进「拉取 N 条」会让用户看着莫名其妙。
     */
    private suspend fun pullAll(
        api: SyncApi,
        from: Long,
        ignore: Set<String> = emptySet(),
    ): Pair<Long, Int> {
        var since = from
        var pulled = 0
        var pages = 0

        while (pages < MAX_PULL_PAGES) {
            val page = api.pull(since)
            pulled += page.recipes.count { it.uuid !in ignore }

            if (page.recipes.isEmpty()) {
                since = maxOf(since, page.nextSince)
                break
            }

            downloadBlobs(api, page.recipes)
            val skipped = store.applyPull(page.recipes)

            val before = since
            since = if (skipped.isEmpty()) {
                // 防御服务端回一个比 since 还小的 nextSince（那样会原地打转）
                maxOf(since, page.nextSince)
            } else {
                // 这一页里有「本地还有没推上去的改动」的记录：水位退到它之前，推完再看
                val earliest = page.recipes.filter { it.uuid in skipped }.minOf { it.rev }
                minOf(page.nextSince, earliest - 1)
            }

            pages += 1
            if (!page.hasMore) break
            // 页首那条被跳过时水位原地不动（`earliest - 1 == since`）：再拉还是同一页、
            // 再落地一遍同样的记录，直到撞上 MAX_PULL_PAGES 白跑 500 轮。
            // 这时的正确做法是**现在就去推**，把这条推完再看下一页。
            if (since <= before) break
        }
        return since to pulled
    }

    /**
     * 把这一批记录引用的图下载下来。**先问一遍「服务端有哪些」**：
     * 一张一张试 404 的话，首次同步会白跑几百个请求。
     */
    private suspend fun downloadBlobs(api: SyncApi, records: List<SyncRecord>) {
        // 哈希 → 是不是缩略图。同一批里同一张图只处理一次（LinkedHashMap 保序，日志好读）
        val kinds = LinkedHashMap<String, Boolean>()
        records.forEach { record ->
            record.payload?.images?.forEach { image ->
                if (image.sha256.isNotEmpty()) kinds.putIfAbsent(image.sha256, false)
                if (image.thumbSha256.isNotEmpty()) kinds.putIfAbsent(image.thumbSha256, true)
            }
        }
        if (kinds.isEmpty()) return

        val wanted = kinds.keys.filter { store.blobPath(it, thumb = kinds.getValue(it)) == null }
        if (wanted.isEmpty()) return

        val absentOnServer = api.missingBlobs(wanted).toSet()
        wanted.forEach { hash ->
            if (hash in absentOnServer) return@forEach // 服务端也没有：跳过这张图，别每轮都白试
            val bytes = api.getBlob(hash) ?: return@forEach
            store.storeBlob(hash, bytes, thumb = kinds.getValue(hash))
        }
    }

    /** 上传这一批要传的图。同样先问一遍服务端缺哪些（内容寻址：它有的就不用再传）。 */
    private suspend fun uploadBlobs(api: SyncApi, files: Map<String, File>) {
        if (files.isEmpty()) return
        val absent = api.missingBlobs(files.keys).toSet()
        absent.forEach { hash ->
            val file = files[hash] ?: return@forEach
            if (!file.isFile) return@forEach
            if (file.length() > SyncProtocol.MAX_BLOB_BYTES) return@forEach
            api.putBlob(hash, file.readBytes())
        }
    }

    /** 把底层异常翻成一句人话。这些字符串会直接显示在设置页上。 */
    private fun describe(error: Throwable): String = when (error) {
        // 先看错误码再看状态码：403 既可能是「配对码不对」，也可能是「令牌失效」，两回事
        is SyncHttpException -> when {
            error.code == "bad_code" ->
                "配对码不对或已经过期：去服务端的 data/pair-code.txt 看当前那六位（十分钟换一次）"
            error.code == "device_mismatch" -> "这台设备和服务端的记录对不上，断开后重新配对"
            error.status == 401 || error.status == 403 ->
                "服务端拒绝了请求（${error.status} ${error.code}）：令牌可能失效了；" +
                    "也可能是前面挡着一层要登录的反向代理"
            error.status == 404 -> "服务端不认这个地址（${error.code}）"
            error.status == 429 -> "请求太频繁，被服务端限流了，过一会儿再试"
            error.status in 500..599 -> "服务端出错了（${error.status}）"
            else -> "服务端拒绝了这次同步（${error.status} ${error.code}）"
        }

        is UnknownHostException -> "找不到服务端地址，检查一下填的地址"
        is ConnectException -> "连不上服务端：确认地址、端口，以及服务端有没有在跑"
        is SocketTimeoutException -> "服务端没响应（超时）"
        is NetworkOnMainThreadException -> "App 在界面线程上发了网络请求（这是程序的 bug，不是你的配置问题）"
        is IOException -> "连不上服务端：确认地址、端口，以及服务端有没有在跑"
        else -> error.message ?: error.javaClass.simpleName
    }

    private companion object {
        /** 一页 200 条、最多翻这么多页。防的是服务端分页逻辑出 bug 时无限转圈。 */
        const val MAX_PULL_PAGES = 500

        /** 探路用的短超时：不在家时别让一条不通的局域网地址拖住整轮同步。 */
        const val PROBE_TIMEOUT_MS = 3_000

        /** 最后一个候选（通常是公网）给正常的超时。 */
        const val NORMAL_TIMEOUT_MS = 10_000

        /** 编辑完等这么久再同步：把「来回按返回」的那一串改动合并成一轮。 */
        const val DEBOUNCE_MILLIS = 1_500L
    }
}
