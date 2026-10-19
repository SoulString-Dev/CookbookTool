package org.miu.cookbook.data.sync

import androidx.room.withTransaction
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.BlockKind
import org.miu.cookbook.data.db.CategoryEntity
import org.miu.cookbook.data.db.ContentBlockEntity
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.IngredientEntity
import org.miu.cookbook.data.db.RecipeCategoryCrossRef
import org.miu.cookbook.data.db.RecipeEntity
import org.miu.cookbook.data.db.RecipeImageEntity
import org.miu.cookbook.data.image.ImageStore
import org.miu.cookbook.data.newUuid
import org.miu.cookbook.sync.model.PushDelete
import org.miu.cookbook.sync.model.PushUpsert
import org.miu.cookbook.sync.model.SyncCategoryRef
import org.miu.cookbook.sync.model.SyncContentBlock
import org.miu.cookbook.sync.model.SyncImage
import org.miu.cookbook.sync.model.SyncIngredient
import org.miu.cookbook.sync.model.SyncRecipePayload
import org.miu.cookbook.sync.model.SyncRecord
import java.io.File

/**
 * 本地库与线上格式之间的翻译层：**只碰数据库和文件，不发一个网络请求**。
 *
 * 分成两层写（这里管数据、[SyncEngine] 管协议）纯粹是为了能测：
 * 拉/推的编排里有水位、冲突、分批这些容易错的东西，而它们的正确性都能用「造几条
 * [SyncRecord] 丢进 [applyPull]」验出来，不必起一个服务端。
 *
 * 两条贯穿全文件的规矩：
 * - **按 uuid 落地，并且原地 UPDATE 保留本机 id**。用 REPLACE 换新 id 会触发外键 CASCADE，
 *   把这道菜的饮食记录和图片行一起冲掉——那等于家人跟我同步一次，我吃过的历史就没了。
 * - **本地有改动（dirty）时，绝不用远端内容盖掉它**，交给冲突处理。
 */
class SyncStore(
    private val database: AppDatabase,
    private val baseDir: File,
) {

    private val recipeDao = database.recipeDao()
    private val categoryDao = database.categoryDao()
    private val imageDao = database.imageDao()

    /** 认不出 section / kind 的正文块丢掉，别让一条坏数据把整次同步带崩。 */

    // ------------------------------------------------------------ 发送

    /** 待推送的行，**含墓碑**（家人那边也得知道这道菜没了）。 */
    suspend fun dirtyRecipes(): List<RecipeEntity> = recipeDao.allDirty()

    /**
     * 把一条食谱折成线上载荷，顺手把这次要传的图片记进 [files]（哈希 → 文件）。
     * 返回 null 表示这道菜推不了（基本不会发生，留个兜底）。
     */
    suspend fun buildUpsert(recipe: RecipeEntity, files: MutableMap<String, File>): PushUpsert? {
        val categoriesById = categoryDao.allCategories().associateBy { it.id }
        val categories = categoryDao.linkedCategoryIds(recipe.id)
            .mapNotNull { categoriesById[it] }
            .map { SyncCategoryRef(uuid = it.uuid, name = it.name, sortOrder = it.sortOrder) }

        val ingredients = recipeDao.ingredientsFor(recipe.id).map {
            SyncIngredient(name = it.name, amount = it.amount, unit = it.unit, sortOrder = it.sortOrder)
        }
        val content = recipeDao.contentFor(recipe.id).map {
            SyncContentBlock(
                section = it.section.name,
                kind = it.kind.name,
                level = it.level,
                text = it.text,
                sortOrder = it.sortOrder,
            )
        }
        val images = imageDao.listFor(recipe.id).mapNotNull { resolveImageHashes(it, files) }

        return PushUpsert(
            uuid = recipe.uuid,
            updatedAt = recipe.updatedAt,
            payload = SyncRecipePayload(
                title = recipe.title,
                createdAt = recipe.createdAt,
                updatedAt = recipe.updatedAt,
                categories = categories,
                ingredients = ingredients,
                content = content,
                images = images,
            ),
        )
    }

    fun deleteOf(recipe: RecipeEntity): PushDelete = PushDelete(uuid = recipe.uuid, deletedAt = recipe.deletedAt)

    /**
     * 确保这一行图片有哈希，并把要传的文件登记进 [files]。
     *
     * 存量图片（v3 之前导入的）哈希是空的，第一次同步顺手补算；
     * 文件不在了就返回 null——这张图传不了，但不该挡住整道菜。
     */
    private suspend fun resolveImageHashes(
        image: RecipeImageEntity,
        files: MutableMap<String, File>,
    ): SyncImage? {
        val full = File(baseDir, image.path)
        val thumbFile = if (image.thumbPath.isNotBlank()) File(baseDir, image.thumbPath) else full

        var sha = image.sha256
        if (sha.isEmpty() && full.isFile) sha = ImageStore.sha256Of(full).orEmpty()
        if (sha.isEmpty()) return null

        var thumbSha = image.thumbSha256
        if (thumbSha.isEmpty() && thumbFile != full && thumbFile.isFile) {
            thumbSha = ImageStore.sha256Of(thumbFile).orEmpty()
        }
        if (sha != image.sha256 || thumbSha != image.thumbSha256) {
            imageDao.updateHashes(image.id, sha, thumbSha)
        }

        files[sha] = full
        if (thumbSha.isNotEmpty() && thumbFile.isFile) files[thumbSha] = thumbFile

        return SyncImage(
            sha256 = sha,
            thumbSha256 = thumbSha,
            width = image.width,
            height = image.height,
            sortOrder = image.sortOrder,
            isCover = image.isCover,
        )
    }

    /** 存量图片第一次同步前补算哈希（要读一遍文件，几百张也就几秒）。 */
    suspend fun backfillHashes() {
        imageDao.allMissingHashes().forEach { image ->
            val full = File(baseDir, image.path)
            val thumbFile = if (image.thumbPath.isNotBlank()) File(baseDir, image.thumbPath) else full
            val sha = image.sha256.ifEmpty { if (full.isFile) ImageStore.sha256Of(full).orEmpty() else "" }
            val thumbSha = image.thumbSha256.ifEmpty {
                if (thumbFile != full && thumbFile.isFile) ImageStore.sha256Of(thumbFile).orEmpty() else ""
            }
            if (sha != image.sha256 || thumbSha != image.thumbSha256) {
                imageDao.updateHashes(image.id, sha, thumbSha)
            }
        }
    }

    /** 按哈希找本地已有的文件（上传前用来判断「这张图我这有」）。 */
    suspend fun localBlobFile(sha256: String): File? {
        if (sha256.isEmpty()) return null
        File(baseDir, "${ImageStore.IMAGE_DIR}/$sha256.jpg").takeIf { it.isFile }?.let { return it }
        File(baseDir, "${ImageStore.THUMB_DIR}/$sha256.jpg").takeIf { it.isFile }?.let { return it }
        return blobPath(sha256, thumb = false)?.let { File(baseDir, it) }?.takeIf { it.isFile }
    }

    // ------------------------------------------------------------ 接收

    /**
     * 落地一批拉取到的记录。**返回被跳过的 uuid**：那些行本地还有没推上去的改动，
     * 调用方要把水位卡在它们之前，否则这几个 rev 一过就再也拉不到了。
     */
    suspend fun applyPull(records: List<SyncRecord>): Set<String> = database.withTransaction {
        records.filterNot { applyRecord(it, pushedUpdatedAt = null) }.map { it.uuid }.toSet()
    }

    /**
     * 落地推送响应里的记录。
     *
     * [pushedUpdatedAt] 是这次推上去的 `updatedAt`：服务端回的版本号对应的是**推上去的那一版**，
     * 如果本地 updatedAt 已经变了，说明推送期间用户又改了一道菜，脏标记得留着等下一轮。
     */
    suspend fun applyPush(records: List<SyncRecord>, pushedUpdatedAt: Map<String, Long>) =
        database.withTransaction {
            records.forEach { applyRecord(it, pushedUpdatedAt[it.uuid]) }
        }

    /** @return true = 处理完了（含「本地没有、无需处理」）；false = 本地有更新的改动，跳过了 */
    private suspend fun applyRecord(record: SyncRecord, pushedUpdatedAt: Long?): Boolean {
        val local = recipeDao.findByUuid(record.uuid)

        if (record.deleted) {
            // 本机没有这道菜（或已经是墓碑）：没有东西要删，记个水位就走
            if (local == null || local.deletedAt != 0L) return true
            val at = if (record.updatedAt > 0) record.updatedAt else System.currentTimeMillis()
            recipeDao.applyRemoteTombstone(local.id, at)
            return true
        }

        // 载荷丢了就别当成「这道菜被清空了」：跳过，等下次
        val payload = record.payload ?: return true

        if (local == null) {
            insertRemote(record, payload)
            return true
        }

        if (local.dirty && local.updatedAt != record.updatedAt) {
            // 拉取路径：本地还有没推上去的改动，这次先别盖，水位卡在它之前，等推送时按冲突处理。
            if (pushedUpdatedAt == null) return false
            // 推送路径：只有「推上去的就是现在这一版」才能清脏并接受服务端的结果。
            if (local.updatedAt != pushedUpdatedAt) return false
        }

        updateRemote(local, record, payload)
        return true
    }

    private suspend fun insertRemote(record: SyncRecord, payload: SyncRecipePayload) {
        val id = recipeDao.insert(
            RecipeEntity(
                uuid = record.uuid,
                title = payload.title,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
                deletedAt = 0,
                dirty = false,
                serverRev = record.rev,
            ),
        )
        writeChildren(id, payload)
    }

    /** **原地 UPDATE，不换 id**（换 id 会 CASCADE 掉本机的饮食记录和图片行）。 */
    private suspend fun updateRemote(local: RecipeEntity, record: SyncRecord, payload: SyncRecipePayload) {
        recipeDao.update(
            local.copy(
                title = payload.title,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
                deletedAt = 0,
                dirty = false,
                serverRev = record.rev,
            ),
        )
        writeChildren(local.id, payload)
    }

    /** 原料 / 正文 / 分类 / 图片整套换成载荷里的。**不 touch updated_at、不标脏**：这是落地，不是本机编辑。 */
    private suspend fun writeChildren(recipeId: Long, payload: SyncRecipePayload) {
        recipeDao.deleteIngredientsFor(recipeId)
        if (payload.ingredients.isNotEmpty()) {
            recipeDao.insertIngredients(
                payload.ingredients.map {
                    IngredientEntity(
                        recipeId = recipeId,
                        name = it.name,
                        amount = it.amount,
                        unit = it.unit,
                        sortOrder = it.sortOrder,
                    )
                },
            )
        }

        ContentSection.entries.forEach { recipeDao.deleteContentFor(recipeId, it) }
        val blocks = payload.content.mapNotNull { block ->
            val section = ContentSection.entries.firstOrNull { it.name == block.section } ?: return@mapNotNull null
            val kind = BlockKind.entries.firstOrNull { it.name == block.kind } ?: BlockKind.LIST_ITEM
            ContentBlockEntity(
                recipeId = recipeId,
                section = section,
                kind = kind,
                level = block.level,
                text = block.text,
                sortOrder = block.sortOrder,
            )
        }
        if (blocks.isNotEmpty()) recipeDao.insertContentBlocks(blocks)

        syncCategories(recipeId, payload.categories)

        imageDao.deleteForRecipes(listOf(recipeId))
        val rows = payload.images.mapNotNull { imageRowFor(recipeId, it) }.toMutableList()
        // 远端说没有封面（或封面那张图本地缺文件）时，把第一张顶上，不然列表页没图
        if (rows.isNotEmpty() && rows.none { it.isCover }) rows[0] = rows[0].copy(isCover = true)
        if (rows.isNotEmpty()) imageDao.insertAll(rows)
    }

    private suspend fun syncCategories(recipeId: Long, refs: List<SyncCategoryRef>) {
        categoryDao.unlinkAll(recipeId)
        refs.forEach { ref ->
            categoryDao.link(RecipeCategoryCrossRef(recipeId = recipeId, categoryId = ensureCategory(ref)))
        }
    }

    /**
     * 拿到这个分类的本机 id：**先按 uuid 认，认不到再按名字认**。
     *
     * 两台设备各建过一个「家常菜」，uuid 不同、名字相同。只按 uuid 认会多出一条同名的；
     * 只按名字认会把两家人各自的分类混成一个。所以 uuid 优先、名字兜底，
     * 按名字认到的时候**保留本机 uuid 不回写**（分类没有独立的同步流，uuid 谁赢都无所谓）。
     */
    private suspend fun ensureCategory(ref: SyncCategoryRef): Long {
        val name = ref.name.trim().ifEmpty { "未分类" }
        categoryDao.findByUuid(ref.uuid)?.let { return it.id }
        categoryDao.findByName(name)?.let { return it.id }

        val inserted = categoryDao.insert(
            CategoryEntity(uuid = ref.uuid.ifBlank { newUuid() }, name = name, sortOrder = ref.sortOrder),
        )
        if (inserted > 0) return inserted
        // 插不进去只可能是刚才有别的线程建了同名/同 uuid 的：再查一次
        return checkNotNull(categoryDao.findByUuid(ref.uuid) ?: categoryDao.findByName(name)) {
            "分类落库失败：$name"
        }.id
    }

    private suspend fun imageRowFor(recipeId: Long, image: SyncImage): RecipeImageEntity? {
        if (image.sha256.isEmpty()) return null
        // 图没下下来（服务端也没有）就跳过这一张，别留一条指向空气的记录
        val path = blobPath(image.sha256, thumb = false) ?: return null
        val thumbPath = if (image.thumbSha256.isNotEmpty()) {
            blobPath(image.thumbSha256, thumb = true) ?: path
        } else {
            path
        }
        return RecipeImageEntity(
            recipeId = recipeId,
            path = path,
            thumbPath = thumbPath,
            sha256 = image.sha256,
            thumbSha256 = image.thumbSha256,
            width = image.width,
            height = image.height,
            sortOrder = image.sortOrder,
            isCover = image.isCover,
        )
    }

    /**
     * 按哈希找本地路径。先看内容寻址的文件名（同步下载的图都这么存），
     * 再问库里的存量行（本机拍的图还是随机文件名）。
     */
    suspend fun blobPath(sha256: String, thumb: Boolean): String? {
        if (sha256.isEmpty()) return null
        val dir = if (thumb) ImageStore.THUMB_DIR else ImageStore.IMAGE_DIR
        val candidate = "$dir/$sha256.jpg"
        if (File(baseDir, candidate).isFile) return candidate
        val row = if (thumb) imageDao.findByThumbSha256(sha256) else imageDao.findBySha256(sha256)
        return row?.let { if (thumb) it.thumbPath.ifBlank { it.path } else it.path }?.takeIf { it.isNotEmpty() }
    }

    /**
     * 把下载到的图落到**内容寻址**的位置（`images/<sha256>.jpg`），返回相对路径。
     * 同一张图可能被几道菜引用，按哈希存一份就够了。
     */
    fun storeBlob(sha256: String, bytes: ByteArray, thumb: Boolean): String {
        val dirName = if (thumb) ImageStore.THUMB_DIR else ImageStore.IMAGE_DIR
        val dir = File(baseDir, dirName).apply { mkdirs() }
        val file = File(dir, "$sha256.jpg")
        if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        return "$dirName/$sha256.jpg"
    }
}
