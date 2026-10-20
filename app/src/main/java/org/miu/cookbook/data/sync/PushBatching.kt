package org.miu.cookbook.data.sync

import kotlinx.serialization.encodeToString
import org.miu.cookbook.sync.model.PushDelete
import org.miu.cookbook.sync.model.PushUpsert
import org.miu.cookbook.sync.model.SyncJson
import org.miu.cookbook.sync.model.SyncProtocol

/**
 * 一批推送的字节预算。
 *
 * 服务端的请求体上限是 [SyncProtocol.MAX_JSON_BYTES]（4MB），留四分之一给信封与元数据。
 * 光按条数（[SyncProtocol.MAX_PUSH_ITEMS]）切是不够的：一道塞满图片的菜单条就能超过 4MB，
 * 那样整批 413、水位不前进，这一批**永远**推不上去。
 */
internal val MAX_PUSH_BYTES: Long = SyncProtocol.MAX_JSON_BYTES * 3 / 4

/** 这一条编码成 JSON 之后占多少字节。 */
internal fun encodedSize(upsert: PushUpsert): Long =
    SyncJson.json.encodeToString(upsert).toByteArray().size.toLong()

/**
 * 把一批切成若干段，每段的编码体积不超过 [MAX_PUSH_BYTES]。
 *
 * 删除请求很小，统一挂在第一段上；单条就超预算时不切（切了也没用），
 * 让服务端回 413，由调用方二分重试或直接报错。
 */
internal fun splitPushBySize(
    upserts: List<PushUpsert>,
    deletes: List<PushDelete>,
): List<Pair<List<PushUpsert>, List<PushDelete>>> {
    if (upserts.isEmpty()) return listOf(emptyList<PushUpsert>() to deletes)

    val parts = mutableListOf<Pair<List<PushUpsert>, List<PushDelete>>>()
    var current = mutableListOf<PushUpsert>()
    var size = 0L
    for (upsert in upserts) {
        val itemSize = encodedSize(upsert)
        if (current.isNotEmpty() && size + itemSize > MAX_PUSH_BYTES) {
            parts += current to emptyList()
            current = mutableListOf()
            size = 0L
        }
        current += upsert
        size += itemSize
    }
    if (current.isNotEmpty()) parts += current to emptyList()

    return parts.mapIndexed { index, part ->
        if (index == 0) part.first to (part.second + deletes) else part
    }
}
