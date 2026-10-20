package org.miu.cookbook.sync.server

import org.miu.cookbook.sync.model.PairResponse
import org.miu.cookbook.sync.model.PullResponse
import org.miu.cookbook.sync.model.PushRequest
import org.miu.cookbook.sync.model.PushResponse
import org.miu.cookbook.sync.model.SyncConflict
import org.miu.cookbook.sync.model.SyncJson
import org.miu.cookbook.sync.model.SyncProtocol
import org.miu.cookbook.sync.model.SyncRecord
import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 服务端的全部状态：SQLite（结构化记录）+ [BlobStore]（图片）。
 *
 * 只有一个进程、一台家用 NAS，所以**一条连接 + 一把锁**就够了——比连接池简单得多，
 * 也不会出现「读到的 rev 与写进去的对不上」这类并发坑。所有公开方法都自己拿锁。
 */
class Store(dataDir: File) : AutoCloseable {

    private val lock = ReentrantLock()
    private val conn: Connection
    val blobs: BlobStore

    init {
        dataDir.mkdirs()
        blobs = BlobStore(File(dataDir, "blobs"))
        conn = DriverManager.getConnection("jdbc:sqlite:${File(dataDir, DB_NAME).path}")
        conn.createStatement().use { st ->
            // WAL 让「读」不被「写」挡住；busy_timeout 给磁盘卡顿时一点余地
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA synchronous=NORMAL")
        }
        createSchema()
    }

    private fun createSchema() {
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS device (
              id           TEXT PRIMARY KEY,
              name         TEXT NOT NULL,
              token_hash   TEXT NOT NULL,
              created_at   INTEGER NOT NULL,
              last_seen_at INTEGER NOT NULL DEFAULT 0,
              revoked      INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_device_token ON device(token_hash)",
            "CREATE TABLE IF NOT EXISTS rev_seq (id INTEGER PRIMARY KEY CHECK (id = 1), value INTEGER NOT NULL)",
            "INSERT OR IGNORE INTO rev_seq (id, value) VALUES (1, 0)",
            """
            CREATE TABLE IF NOT EXISTS recipe (
              uuid        TEXT PRIMARY KEY,
              server_rev  INTEGER NOT NULL,
              deleted     INTEGER NOT NULL DEFAULT 0,
              updated_at  INTEGER NOT NULL,
              payload     TEXT NOT NULL DEFAULT '',
              origin_dev  TEXT NOT NULL DEFAULT '',
              server_time INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_recipe_rev ON recipe(server_rev)",
            "CREATE TABLE IF NOT EXISTS blob (sha256 TEXT PRIMARY KEY, size INTEGER NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pair_code (code TEXT PRIMARY KEY, created_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0)",
        )
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    // ------------------------------------------------------------ 基础

    private fun <T> read(block: () -> T): T = lock.withLock { block() }

    private fun <T> write(block: () -> T): T = lock.withLock {
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { index, arg -> ps.setObject(index + 1, arg) }
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out += map(rs)
                return out
            }
        }
    }

    private fun execute(sql: String, vararg args: Any?): Int =
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { index, arg -> ps.setObject(index + 1, arg) }
            ps.executeUpdate()
        }

    private fun nextRev(): Long {
        execute("UPDATE rev_seq SET value = value + 1 WHERE id = 1")
        return query("SELECT value FROM rev_seq WHERE id = 1") { it.getLong(1) }.first()
    }

    fun rev(): Long = read { query("SELECT value FROM rev_seq WHERE id = 1") { it.getLong(1) }.first() }

    fun recipeCount(): Long = read { query("SELECT COUNT(*) FROM recipe WHERE deleted = 0") { it.getLong(1) }.first() }

    override fun close() = conn.close()

    /**
     * 导出一致性快照。用 SQLite 自己的 `VACUUM INTO`：它把 WAL 里还没合并的页也一起算上，
     * 所以**对着正在跑的实例执行也是安全的**（读快照不会被写挡住）。
     *
     * 图片不在库里（见 [BlobStore]），备份要连 `blobs/` 目录一起拷——那里面按内容寻址、
     * 落盘是「临时文件 + 改名」，运行中直接拷也是安全的。
     */
    fun backupTo(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) error("删不掉旧的备份文件：${target.path}")
        val escaped = target.absolutePath.replace("'", "''")
        // 故意不走 write{}：VACUUM 不能在事务里跑
        read { conn.createStatement().use { it.execute("VACUUM INTO '$escaped'") } }
    }


    // ------------------------------------------------------------ 设备与配对

    /**
     * 配对。[fixedCode] 非空时只认它；否则从 `pair_code` 表里找一个未使用且未过期的码。
     *
     * @return null 表示配对码不对
     */
    fun pair(code: String, deviceName: String, fixedCode: String, now: Long = System.currentTimeMillis()): PairResponse? {
        val normalized = code.trim()
        val ok = write {
            if (fixedCode.isNotEmpty()) {
                normalized == fixedCode
            } else {
                val hit = query(
                    "SELECT code FROM pair_code WHERE code = ? AND used = 0 AND created_at > ?",
                    normalized,
                    now - PAIR_CODE_TTL_MS,
                ) { it.getString(1) }.firstOrNull()
                if (hit == null) false else execute("UPDATE pair_code SET used = 1 WHERE code = ?", hit) > 0
            }
        }
        if (!ok) return null

        val deviceId = UUID.randomUUID().toString()
        val token = randomHex(32)
        write {
            execute(
                "INSERT INTO device (id, name, token_hash, created_at, last_seen_at, revoked) VALUES (?, ?, ?, ?, ?, 0)",
                deviceId,
                deviceName.ifBlank { "未命名设备" },
                sha256Hex(token),
                now,
                now,
            )
        }
        log("新设备配对：$deviceId（${deviceName.ifBlank { "未命名设备" }}）")
        return PairResponse(deviceId = deviceId, token = token, serverRev = rev())
    }

    /** 校验 Bearer 令牌。返回设备 id；不认识或已吊销返回 null。 */
    fun authenticate(token: String): String? = read {
        query(
            "SELECT id FROM device WHERE token_hash = ? AND revoked = 0",
            sha256Hex(token),
        ) { it.getString(1) }.firstOrNull()
    }

    fun deviceName(deviceId: String): String =
        read { query("SELECT name FROM device WHERE id = ?", deviceId) { it.getString(1) }.firstOrNull() }.orEmpty()

    private fun touchDevice(deviceId: String, now: Long) {
        execute("UPDATE device SET last_seen_at = ? WHERE id = ?", now, deviceId)
    }

    /** 当前可用的一次性配对码（没有就返回 null）。 */
    fun currentPairCode(now: Long = System.currentTimeMillis()): String? = read {
        query(
            "SELECT code FROM pair_code WHERE used = 0 AND created_at > ? ORDER BY created_at DESC LIMIT 1",
            now - PAIR_CODE_TTL_MS,
        ) { it.getString(1) }.firstOrNull()
    }

    /** 生成一个新的配对码并写库。返回码本身。 */
    fun newPairCode(now: Long = System.currentTimeMillis()): String = write {
        val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
        execute("INSERT OR REPLACE INTO pair_code (code, created_at, used) VALUES (?, ?, 0)", code, now)
        code
    }

    // ------------------------------------------------------------ 拉取

    /**
     * 增量拉取。**水位按记录自己的 rev 推进**：返回的 [PullResponse.nextSince] 是本次最后一条的 rev，
     * 而不是全局水位——否则分页途中别人一写，中间那几条就被永久跳过了。
     *
     * 客户端的水位比服务端还高（服务端被回滚 / 换了库）时，当作从头拉，让它能自愈。
     */
    fun pull(since: Long, limit: Int): PullResponse = read {
        val current = rev()
        val from = if (since > current) 0L else since
        val rows = query(
            "SELECT uuid, server_rev, deleted, updated_at, payload FROM recipe WHERE server_rev > ? ORDER BY server_rev ASC LIMIT ?",
            from,
            limit + 1,
        ) { rowToStored(it) }
        val hasMore = rows.size > limit
        val page = if (hasMore) rows.take(limit) else rows
        PullResponse(
            serverRev = current,
            nextSince = page.lastOrNull()?.rev ?: from,
            hasMore = hasMore,
            recipes = page.mapNotNull { it.toRecord() },
        )
    }

    // ------------------------------------------------------------ 推送

    /**
     * 推送增量。逐条判断冲突：`该记录的 server_rev > baseRev` 就说明它在客户端上次拉取之后
     * 被别的设备改过，此时按 `updatedAt` 较新者胜，**败方以新 uuid 存成带「（冲突副本）」后缀的副本**。
     *
     * 删除不产生副本：给一条已删的菜留个副本只会让人困惑。
     */
    fun push(deviceId: String, request: PushRequest, now: Long = System.currentTimeMillis()): PushResponse = write {
        val before = rev()
        val records = LinkedHashMap<String, SyncRecord>()
        val conflicts = ArrayList<SyncConflict>()

        for (upsert in request.upserts) {
            val existing = selectOne(upsert.uuid)
            if (existing == null) {
                val rev = nextRev()
                insert(upsert.uuid, rev, deleted = false, updatedAt = upsert.updatedAt, payload = SyncJson.encodePayload(upsert.payload), deviceId, now)
                records[upsert.uuid] = SyncRecord(upsert.uuid, rev, false, upsert.updatedAt, upsert.payload)
                continue
            }
            // 幂等：内容和时间都没变的重推不再发号（断网重试是家常便饭）
            if (!existing.deleted && existing.updatedAt == upsert.updatedAt) {
                records[upsert.uuid] = existing.toRecord() ?: continue
                continue
            }
            // 撞墓碑：客户端还在编辑一道已经被删掉的菜。
            // 这条**不能**走下面的冲突分支——墓碑的 payload 是空串，`insertCopy("")` 会落一条
            // `deleted = 0, payload = ''` 的僵尸行：客户端拉下来解析出 null 直接跳过，
            // `recipeCount()` 却多算一条，gc 也永远删不掉它。
            // 按「较新者胜」办：客户端的改动更新就原地复活（同一个 uuid，不发副本），
            // 否则回墓碑并报一条「服务端赢」的冲突（和删除冲突一样，不产生副本）。
            if (existing.deleted) {
                if (upsert.updatedAt > existing.updatedAt) {
                    val rev = nextRev()
                    updateRecord(
                        upsert.uuid, rev, deleted = false, updatedAt = upsert.updatedAt,
                        payload = SyncJson.encodePayload(upsert.payload), deviceId, now,
                    )
                    records[upsert.uuid] = SyncRecord(upsert.uuid, rev, false, upsert.updatedAt, upsert.payload)
                } else {
                    records[upsert.uuid] = existing.toRecord() ?: continue
                    conflicts += SyncConflict(upsert.uuid, "server", existing.updatedAt, upsert.updatedAt, "")
                }
                continue
            }

            val stale = existing.rev > request.baseRev
            if (!stale) {
                val rev = nextRev()
                updateRecord(upsert.uuid, rev, deleted = false, updatedAt = upsert.updatedAt, payload = SyncJson.encodePayload(upsert.payload), deviceId, now)
                records[upsert.uuid] = SyncRecord(upsert.uuid, rev, false, upsert.updatedAt, upsert.payload)
                continue
            }
            // 冲突
            if (upsert.updatedAt > existing.updatedAt) {
                val copy = insertCopy(existing.payload, existing.updatedAt, deviceId, now)
                val rev = nextRev()
                updateRecord(upsert.uuid, rev, deleted = false, updatedAt = upsert.updatedAt, payload = SyncJson.encodePayload(upsert.payload), deviceId, now)
                records[upsert.uuid] = SyncRecord(upsert.uuid, rev, false, upsert.updatedAt, upsert.payload)
                records[copy.uuid] = copy
                conflicts += SyncConflict(upsert.uuid, "client", existing.updatedAt, upsert.updatedAt, copy.uuid)
            } else {
                val copy = insertCopy(SyncJson.encodePayload(upsert.payload), upsert.updatedAt, deviceId, now)
                records[upsert.uuid] = existing.toRecord() ?: continue
                records[copy.uuid] = copy
                conflicts += SyncConflict(upsert.uuid, "server", existing.updatedAt, upsert.updatedAt, copy.uuid)
            }
        }

        for (delete in request.deletes) {
            val existing = selectOne(delete.uuid)
            if (existing == null) {
                val rev = nextRev()
                insert(delete.uuid, rev, deleted = true, updatedAt = delete.deletedAt, payload = "", deviceId, now)
                records[delete.uuid] = SyncRecord(delete.uuid, rev, true, delete.deletedAt, null)
                continue
            }
            if (existing.deleted) {
                records[delete.uuid] = existing.toRecord() ?: continue
                continue
            }
            if (existing.rev > request.baseRev && delete.deletedAt <= existing.updatedAt) {
                // 别人的修改更新，这次删除作废
                records[delete.uuid] = existing.toRecord() ?: continue
                conflicts += SyncConflict(delete.uuid, "server", existing.updatedAt, delete.deletedAt, "")
                continue
            }
            val rev = nextRev()
            updateRecord(delete.uuid, rev, deleted = true, updatedAt = delete.deletedAt, payload = "", deviceId, now)
            records[delete.uuid] = SyncRecord(delete.uuid, rev, true, delete.deletedAt, null)
        }

        if (records.isNotEmpty()) touchDevice(deviceId, now)
        PushResponse(serverRev = before, records = records.values.toList(), conflicts = conflicts)
    }

    // ------------------------------------------------------------ 图片登记

    fun rememberBlob(sha256: String, size: Int, now: Long = System.currentTimeMillis()) {
        write { execute("INSERT OR REPLACE INTO blob (sha256, size, created_at) VALUES (?, ?, ?)", sha256, size, now) }
    }

    /**
     * 清理：墓碑到期物理删；没人引用、且**已经放了一阵子**的图片也删掉。
     *
     * 图片有「先传图再推记录」的空窗期，所以只删 24 小时前上传的孤儿图。
     */
    fun gc(tombstoneDays: Int, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val tombstones = write {
            execute("DELETE FROM recipe WHERE deleted = 1 AND server_time < ?", now - tombstoneDays * 86_400_000L)
        }
        val referenced = HashSet<String>()
        read {
            query("SELECT payload FROM recipe WHERE deleted = 0 AND payload <> ''") { it.getString(1) }
        }.forEach { text ->
            runCatching { SyncJson.decodePayload(text).blobHashes() }.getOrNull()?.let { referenced += it }
        }
        val cutoff = now - ORPHAN_GRACE_MS
        val removed = blobs.deleteUnreferencedOlderThan(cutoff, referenced)
        write {
            query("SELECT sha256 FROM blob WHERE created_at < ?", cutoff) { it.getString(1) }
        }.forEach { sha ->
            if (sha !in referenced && !blobs.exists(sha)) execute("DELETE FROM blob WHERE sha256 = ?", sha)
        }
        return tombstones to removed
    }

    // ------------------------------------------------------------ 行与记录的转换

    private class StoredRecord(
        val uuid: String,
        val rev: Long,
        val deleted: Boolean,
        val updatedAt: Long,
        val payload: String,
    ) {
        /** 载荷读不出来时返回 null（调用方会跳过这条并留日志）。 */
        fun toRecord(): SyncRecord? {
            if (deleted) return SyncRecord(uuid, rev, true, updatedAt, null)
            val parsed = try {
                SyncJson.decodePayload(payload)
            } catch (e: Exception) {
                log("记录 $uuid 的载荷读不出来，跳过：${e.message}")
                return null
            }
            return SyncRecord(uuid, rev, false, updatedAt, parsed)
        }
    }

    private fun rowToStored(rs: ResultSet) = StoredRecord(
        uuid = rs.getString("uuid"),
        rev = rs.getLong("server_rev"),
        deleted = rs.getInt("deleted") != 0,
        updatedAt = rs.getLong("updated_at"),
        payload = rs.getString("payload") ?: "",
    )

    private fun selectOne(uuid: String): StoredRecord? =
        query(
            "SELECT uuid, server_rev, deleted, updated_at, payload FROM recipe WHERE uuid = ?",
            uuid,
        ) { rowToStored(it) }.firstOrNull()

    private fun insert(uuid: String, rev: Long, deleted: Boolean, updatedAt: Long, payload: String, deviceId: String, now: Long) {
        execute(
            "INSERT INTO recipe (uuid, server_rev, deleted, updated_at, payload, origin_dev, server_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
            uuid, rev, if (deleted) 1 else 0, updatedAt, payload, deviceId, now,
        )
    }

    private fun updateRecord(uuid: String, rev: Long, deleted: Boolean, updatedAt: Long, payload: String, deviceId: String, now: Long) {
        execute(
            "UPDATE recipe SET server_rev = ?, deleted = ?, updated_at = ?, payload = ?, origin_dev = ?, server_time = ? WHERE uuid = ?",
            rev, if (deleted) 1 else 0, updatedAt, payload, deviceId, now, uuid,
        )
    }

    /**
     * 把「败方」存成新 uuid 的副本，菜名加后缀。载荷解析不了就原样存（宁可留个坏副本，也别把用户的内容丢了）。
     */
    private fun insertCopy(loserPayload: String, loserUpdatedAt: Long, deviceId: String, now: Long): SyncRecord {
        val payload = renameForCopy(loserPayload)

        // 重推保护：请求在服务端处理完之后断线，客户端会把一模一样的一批再发一次。
        // 上一轮已经为同一份内容建过副本的话，把它原样还回去——否则每次重试都多一个副本。
        findExistingCopy(payload, loserUpdatedAt, deviceId)?.let { return it }

        val uuid = UUID.randomUUID().toString()
        val rev = nextRev()
        insert(uuid, rev, deleted = false, updatedAt = loserUpdatedAt, payload = payload, deviceId = deviceId, now = now)
        val parsed = runCatching { SyncJson.decodePayload(payload) }.getOrNull()
        return SyncRecord(uuid, rev, false, loserUpdatedAt, parsed)
    }

    /** 找同一台设备、同一时间戳、同一内容的既有副本（重推时用）。 */
    private fun findExistingCopy(payload: String, loserUpdatedAt: Long, deviceId: String): SyncRecord? =
        query(
            "SELECT uuid, server_rev, deleted, updated_at, payload FROM recipe " +
                "WHERE deleted = 0 AND origin_dev = ? AND updated_at = ? AND payload = ? LIMIT 1",
            deviceId, loserUpdatedAt, payload,
        ) { rowToStored(it) }.firstOrNull()?.toRecord()

    private fun renameForCopy(payload: String): String {
        if (payload.isBlank()) return payload
        val parsed = runCatching { SyncJson.decodePayload(payload) }.getOrNull() ?: return payload
        return SyncJson.encodePayload(
            parsed.copy(title = parsed.title + SyncProtocol.CONFLICT_SUFFIX),
        )
    }

    companion object {
        const val DB_NAME = "cookbook-sync.db"

        const val PAIR_CODE_TTL_MS = 10 * 60 * 1000L
        const val ORPHAN_GRACE_MS = 24 * 60 * 60 * 1000L
    }
}
