package org.miu.cookbook.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.SyncStateEntity
import org.miu.cookbook.sync.model.PushDelete
import org.miu.cookbook.sync.model.PushRequest
import org.miu.cookbook.sync.model.PushUpsert
import org.miu.cookbook.sync.model.SyncProtocol
import org.miu.cookbook.sync.model.SyncRecord
import java.io.File
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
) {

    private val store = SyncStore(database, baseDir)
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val paired: Boolean get() = credentials.paired

    /**
     * 配对：换令牌、存地址、**把游标归零**。
     *
     * 归零这一步很容易漏：配对时服务端会回一个 `serverRev`，顺手拿它当水位的话，
     * 新设备第一次同步会把服务端上已有的记录全部跳过——看起来「同步成功」，其实一张菜谱都没下来。
     */
    suspend fun pair(serverUrl: String, code: String, deviceName: String): Result<Unit> = runCatching {
        val api = SyncApi(serverUrl.trim())
        val response = api.pair(code.trim(), deviceName.trim())
        credentials.token = response.token
        credentials.deviceName = deviceName.trim()

        val current = repository.currentSyncState() ?: SyncStateEntity()
        repository.saveSyncState(
            current.copy(
                serverUrl = serverUrl.trim(),
                // 服务端发的设备号，不是本机自己编的那个：令牌跟它绑定，推的时候必须带同一个
                deviceId = response.deviceId,
                lastRev = 0,
                lastSyncAt = 0,
                lastError = "",
            ),
        )
        _status.value = SyncStatus.Idle
    }.onFailure { _status.value = SyncStatus.Failed(describe(it)) }

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
        val state = repository.currentSyncState()
        if (!credentials.paired || state == null || state.serverUrl.isBlank()) {
            return Result.failure(IllegalStateException("还没配对"))
        }

        _status.value = SyncStatus.Running
        runCatching { syncOnce(state) }
            .onSuccess { _status.value = SyncStatus.Done(it, System.currentTimeMillis()) }
            .onFailure { error ->
                val message = describe(error)
                _status.value = SyncStatus.Failed(message)
                runCatching {
                    repository.currentSyncState()?.let { repository.saveSyncState(it.copy(lastError = message)) }
                }
            }
    }

    private suspend fun syncOnce(state: SyncStateEntity): SyncReport {
        val api = SyncApi(state.serverUrl, credentials.token)
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
            latest.copy(lastRev = since, lastSyncAt = System.currentTimeMillis(), lastError = ""),
        )
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
        is SyncHttpException -> when (error.status) {
            401, 403 -> "这个设备的令牌失效了，断开后重新配对"
            404 -> "服务端不认这个地址（${error.code}）"
            429 -> "请求太频繁，被服务端限流了，过一会儿再试"
            in 500..599 -> "服务端出错了（${error.status}）"
            else -> "服务端拒绝了这次同步（${error.status} ${error.code}）"
        }

        is UnknownHostException -> "找不到服务端地址，检查一下填的地址"
        is ConnectException -> "连不上服务端：确认地址、端口，以及服务端有没有在跑"
        is SocketTimeoutException -> "服务端没响应（超时）"
        else -> error.message ?: error.javaClass.simpleName
    }

    private companion object {
        /** 一页 200 条、最多翻这么多页。防的是服务端分页逻辑出 bug 时无限转圈。 */
        const val MAX_PULL_PAGES = 500
    }
}
