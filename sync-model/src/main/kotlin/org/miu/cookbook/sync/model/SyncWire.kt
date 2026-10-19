package org.miu.cookbook.sync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 家庭共享的线上格式（App 与服务端共用一份，避免两端字段各写各的）。
 *
 * 三个概念要分清：
 * - **记录（[SyncRecord]）**：服务端认识的最小单位，身份是 [SyncRecord.uuid]，带自己的版本号 [SyncRecord.rev]。
 * - **载荷（[SyncRecipePayload]）**：一条食谱的全部内容。**故意不带本机自增 id，也不带图片路径**——
 *   那些东西换台设备就没有意义了；图片改用内容哈希 [SyncImage.sha256] 引用。
 * - **水位（rev）**：服务端每接受一次改动就发一个全局递增的 rev。客户端只记「我连续拉到哪儿」。
 */
object SyncProtocol {

    /** 与备份文件（`cookbook-backup`）是两个东西，别混。 */
    const val FORMAT = "cookbook-sync"
    const val VERSION = 1

    /** 一次推送最多带这么多条（服务端按同一条规矩校验）。首次全量同步要自己分批。 */
    const val MAX_PUSH_ITEMS = 50

    /** 一次拉取最多返回多少条。 */
    const val MAX_PULL_LIMIT = 200

    /** 单张图片上限，服务端超了直接拒。 */
    const val MAX_BLOB_BYTES = 10L * 1024 * 1024

    /** JSON 请求体上限（推送 50 条食谱也不该超过它）。 */
    const val MAX_JSON_BYTES = 4L * 1024 * 1024

    /** 冲突败方的菜名后缀。服务端负责加，客户端别自己加，否则两边都加会变成「（冲突副本）（冲突副本）」。 */
    const val CONFLICT_SUFFIX = "（冲突副本）"

    const val PATH_HEALTH = "/api/v1/health"
    const val PATH_PAIR = "/api/v1/pair"
    const val PATH_SYNC = "/api/v1/sync"
    const val PATH_BLOB_CHECK = "/api/v1/blobs/check"
    const val PATH_BLOB_PREFIX = "/api/v1/blobs/"

    fun blob(sha256: String): String = PATH_BLOB_PREFIX + sha256
}

/** 线上 JSON 的编解码。`ignoreUnknownKeys` 让旧客户端遇到新字段不会直接炸。 */
object SyncJson {

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodePayload(payload: SyncRecipePayload): String = json.encodeToString(payload)

    /** 解析失败就是失败：调用方自己决定是丢弃还是当冲突处理，别在这里吞异常。 */
    fun decodePayload(text: String): SyncRecipePayload = json.decodeFromString(text)
}

// ---------------------------------------------------------------- 载荷

/**
 * 分类引用。**必须用 uuid 而不是本机 id**：两台设备各自建的「家常菜」id 都是 1，
 * 但它们是两个分类；只按名字或 id 合并会把两家人的分类串在一起。
 * 名字也一起带上，是为了接收端在本地没有这个 uuid 时能直接建出来（分类没有独立同步流）。
 */
@Serializable
data class SyncCategoryRef(
    val uuid: String,
    val name: String,
    val sortOrder: Int = 0,
)

@Serializable
data class SyncIngredient(
    val name: String,
    val amount: String = "",
    val unit: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class SyncContentBlock(
    /** `DIRECTIONS` 或 `NOTE`。 */
    val section: String,
    /** `LIST_ITEM` 或 `HEADING`。 */
    val kind: String,
    val level: Int = 0,
    val text: String = "",
    val sortOrder: Int = 0,
)

/**
 * 图片。老备份里只有本机路径，同步时必须先补上 sha256 才能发（服务端按内容寻址）。
 * 缩略图也走同一个 [sha256] 空间，各算各的哈希。
 */
@Serializable
data class SyncImage(
    val sha256: String,
    val thumbSha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val sortOrder: Int = 0,
    val isCover: Boolean = false,
)

/**
 * 一条食谱的全部内容。**与备份文件里的 `BackupRecipe` 类似但不同构**：见上面的类注释，
 * 本地 id 与图片路径都不上线。
 */
@Serializable
data class SyncRecipePayload(
    val title: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val categories: List<SyncCategoryRef> = emptyList(),
    val ingredients: List<SyncIngredient> = emptyList(),
    val content: List<SyncContentBlock> = emptyList(),
    val images: List<SyncImage> = emptyList(),
) {
    /** 载荷里引用到的所有图片哈希，用来算「要传哪些图」。 */
    fun blobHashes(): List<String> =
        images.flatMap { listOf(it.sha256, it.thumbSha256) }.filter { it.isNotEmpty() }
}

// ---------------------------------------------------------------- 记录与同步

/** 服务端认识的一条记录。[rev] 是**它自己的**版本号，客户端靠它推进水位，不能拿全局水位代替。 */
@Serializable
data class SyncRecord(
    val uuid: String,
    /** 服务端序列号；推送时客户端不用填（服务端自己发号）。 */
    val rev: Long = 0,
    val deleted: Boolean = false,
    /** 客户端时钟（删除的那条是删除时间），只用来比冲突，别拿它做别的。 */
    val updatedAt: Long = 0,
    /** 墓碑没有载荷。 */
    val payload: SyncRecipePayload? = null,
)

@Serializable
data class PullResponse(
    val format: String = SyncProtocol.FORMAT,
    val version: Int = SyncProtocol.VERSION,
    /** 服务端当前水位，仅供参考与「服务端被回滚了」的自愈判断。 */
    val serverRev: Long = 0,
    /**
     * **客户端把 lastRev 设成这个值**（不是 serverRev）。
     * 它是本次实际返回的最后一条的 rev：分页途中水位再涨也不会漏掉中间那几条。
     */
    val nextSince: Long = 0,
    val hasMore: Boolean = false,
    val recipes: List<SyncRecord> = emptyList(),
)

@Serializable
data class PushUpsert(
    val uuid: String,
    val updatedAt: Long,
    val payload: SyncRecipePayload,
)

@Serializable
data class PushDelete(
    val uuid: String,
    val deletedAt: Long,
)

@Serializable
data class PushRequest(
    val deviceId: String = "",
    /** 上次同步后客户端见到的水位。服务端用它判断「这条记录你是否见过」。 */
    val baseRev: Long = 0,
    val upserts: List<PushUpsert> = emptyList(),
    val deletes: List<PushDelete> = emptyList(),
)

@Serializable
data class SyncConflict(
    val uuid: String,
    /** `"client"` = 这次推的赢了；`"server"` = 服务端原样保留，这次推的被挪成副本。 */
    val winner: String,
    val serverUpdatedAt: Long = 0,
    val clientUpdatedAt: Long = 0,
    /** 败方那条在服务端的新 uuid；[PushResponse.records] 里能找到它。删除不产生副本，这里是空串。 */
    val copyUuid: String = "",
)

@Serializable
data class PushResponse(
    val serverRev: Long = 0,
    /**
     * 这次推送期间服务端落地的记录（含冲突副本、以及「服务端胜出」的那条）。
     * 客户端按 uuid 原地应用即可，**不要拿 serverRev 去推进 lastRev**：
     * 服务端水位在你推送期间可能被别人推高了，直接跳过去会永久漏掉别人的改动。
     */
    val records: List<SyncRecord> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
)

@Serializable
data class PairRequest(val code: String, val deviceName: String = "")

@Serializable
data class PairResponse(
    val deviceId: String,
    val token: String,
    val serverRev: Long = 0,
    val format: String = SyncProtocol.FORMAT,
    val version: Int = SyncProtocol.VERSION,
)

/** 批量问「这些图你有吗」。逐张问会在首次同步时打出几百个请求。 */
@Serializable
data class BlobCheckRequest(val sha256: List<String> = emptyList())

@Serializable
data class BlobCheckResponse(val missing: List<String> = emptyList())

@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    val version: String = "",
    val serverRev: Long = 0,
    val recipes: Long = 0,
    val serverTime: Long = 0,
)

@Serializable
data class ApiError(val error: String, val message: String = "")
