package org.miu.cookbook.data

import androidx.room.withTransaction
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.miu.cookbook.data.backup.BackupArchive
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.BlockKind
import org.miu.cookbook.data.db.CategoryDao
import org.miu.cookbook.data.db.CategoryEntity
import org.miu.cookbook.data.db.ContentBlockEntity
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.IngredientEntity
import org.miu.cookbook.data.db.MealLogDao
import org.miu.cookbook.data.db.MealLogEntity
import org.miu.cookbook.data.db.MealLogRow
import org.miu.cookbook.data.db.RecipeCategoryCrossRef
import org.miu.cookbook.data.db.RecipeDao
import org.miu.cookbook.data.db.RecipeImageDao
import org.miu.cookbook.data.db.RecipeImageEntity
import org.miu.cookbook.data.db.RecipeDetail
import org.miu.cookbook.data.db.RecipeEntity
import org.miu.cookbook.data.db.RecipeListRow
import org.miu.cookbook.data.db.SyncStateDao
import org.miu.cookbook.data.db.SyncStateEntity
import org.miu.cookbook.data.image.ImageStore
import org.miu.cookbook.data.image.ImportedImage
import org.miu.cookbook.domain.backup.BackupCategory
import org.miu.cookbook.domain.backup.BackupContentBlock
import org.miu.cookbook.domain.backup.BackupFile
import org.miu.cookbook.domain.backup.BackupImage
import org.miu.cookbook.domain.backup.BackupIngredient
import org.miu.cookbook.domain.backup.BackupMealLog
import org.miu.cookbook.domain.backup.BackupRecipe
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.sync.model.SyncProtocol
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.ContentOutline
import org.miu.cookbook.domain.recipe.IngredientLine
import org.miu.cookbook.domain.recipe.MAX_RECIPE_IMAGES

/**
 * 界面唯一的数据入口。读直接透传 Room 的 Flow，写统一走 IO 线程。
 *
 * 没有引 Repository 接口 + 实现那一套：只有一个实现，抽接口除了多一层跳转
 * 没有任何好处；测试要替身的话传个假 DAO 就够了。
 */
class CookbookRepository(
    /** 拿整个库而不是四个 DAO：导入要在一个事务里清空并写回，还得用 clearAllTables。 */
    private val database: AppDatabase,
    /** 图片存放的根目录，一般就是 `filesDir`。 */
    private val baseDir: java.io.File,
) {

    private val recipeDao: RecipeDao = database.recipeDao()
    private val categoryDao: CategoryDao = database.categoryDao()
    private val mealLogDao: MealLogDao = database.mealLogDao()
    private val imageDao: RecipeImageDao = database.imageDao()
    private val syncStateDao: SyncStateDao = database.syncStateDao()

    // ---------- 读 ----------

    /**
     * 这些都是**属性而不是函数**：每次调用 DAO 的 `observeXxx()` 都会新建一个
     * Flow 对象，而 Compose 的 `collectAsStateWithLifecycle` 拿 Flow 实例当 key，
     * 写成函数的话每次重组都会重新订阅一遍。
     */
    val recipeList: Flow<List<RecipeListRow>> = recipeDao.observeAllWithDetails()

    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun recipeDetail(id: Long): Flow<RecipeDetail?> = recipeDao.observeDetail(id)

    /** 库里所有分类的 uuid（侧栏拿它剪掉已经没了的分类）。 */
    suspend fun categoryUuids(): Set<String> = io { categoryDao.allCategories().mapTo(HashSet()) { it.uuid } }

    suspend fun linkedCategoryIds(recipeId: Long): Set<Long> =
        io { categoryDao.linkedCategoryIds(recipeId).toSet() }

    // ---------- 写 ----------

    /** 新建食谱，返回新食谱 id。 */
    suspend fun createRecipe(title: String): Long = io {
        val now = System.currentTimeMillis()
        recipeDao.insert(
            RecipeEntity(
                uuid = newUuid(),
                title = title.trim(),
                createdAt = now,
                updatedAt = now,
                // dirty 的默认值就是 true，这里显式写出来是为了让「新建 = 要上传」一眼可见
                dirty = true,
            ),
        )
    }

    /** 改名。空名字直接忽略——界面上保存按钮会拦住，这里再兜一层。 */
    suspend fun renameRecipe(id: Long, title: String) = io {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@io
        recipeDao.updateTitle(id, trimmed, System.currentTimeMillis())
    }

    /**
     * 批量删除食谱。
     *
     * 这里删的是**本机**的这份数据，所以语义和 v1 一样：记录、图片都跟着走。
     * 数据库里留的是墓碑（`deleted_at` 非 0），不是物理删——「删掉」这件事要能同步给家人。
     * 远端推来的墓碑走的是另一条路，那条路不动本机饮食记录。
     */
    suspend fun deleteRecipes(ids: List<Long>) = io {
        if (ids.isEmpty()) return@io
        val now = System.currentTimeMillis()
        val images = database.withTransaction {
            val found = ids.flatMap { imageDao.listFor(it) }
            imageDao.deleteForRecipes(ids)
            mealLogDao.deleteForRecipes(ids)
            recipeDao.softDeleteByIds(ids, now)
            found
        }
        // 文件删除放在事务外：删磁盘不该占着数据库事务（图片行已经在事务里没了）。
        // 而且**要确认没有别的行还在引用同一个文件**：同步落地的图按内容寻址
        // （`images/<sha256>.jpg`），两张菜用同一张图时只有一个文件。
        images.forEach { deleteFilesIfUnreferenced(it.path, it.thumbPath) }
    }

    /** 这些食谱下共有多少条饮食记录，用于删除前的提示。 */
    suspend fun countMealLogs(recipeIds: List<Long>): Int = io {
        if (recipeIds.isEmpty()) 0 else mealLogDao.countForRecipes(recipeIds)
    }

    // ---------- 饮食历史 ----------

    /** 历史页的数据：全部记录按时间正序（老日子在前），列表默认停在最下面。 */
    val mealHistory: Flow<List<MealLogRow>> = mealLogDao.observeHistory()

    /** 某道菜自己的历史，按时间倒序（详情页那个浮窗）。 */
    fun mealHistoryOf(recipeId: Long): Flow<List<MealLogRow>> =
        mealLogDao.observeForRecipe(recipeId)

    /** 记一顿：同一天同一餐可以有多道菜（一餐多菜）。 */
    suspend fun logMeal(recipeId: Long, epochDay: Long, slot: MealSlot): Long = io {
        mealLogDao.insert(
            MealLogEntity(
                epochDay = epochDay,
                slot = slot,
                recipeId = recipeId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 撤销一条记录 / 从某天里删掉一道菜。 */
    suspend fun deleteMealLogs(ids: List<Long>) = io {
        if (ids.isNotEmpty()) mealLogDao.deleteByIds(ids)
    }

    /** 整天删掉（长按历史列表的「删除」）。 */
    suspend fun deleteMealDay(day: Long) = io { mealLogDao.deleteForDay(day) }

    suspend fun countMealLogsForDay(day: Long): Int = io { mealLogDao.countForDay(day) }

    /**
     * 把某天的一餐改到任意一天的任意一餐（跨天也行，记错日期是最常见的手滑）。
     *
     * **目标那餐已经有记录就整体交换**（日期和餐段一起换回来），返回是否发生了交换，
     * 界面拿这个返回值提示用户一声；没有记录就直接挪过去。
     */
    suspend fun moveMealSlotTo(
        fromDay: Long,
        fromSlot: MealSlot,
        toDay: Long,
        toSlot: MealSlot,
    ): Boolean = io { mealLogDao.moveOrSwapSlots(fromDay, fromSlot, toDay, toSlot) }

    /** 改名（详情页编辑态用）。 */
    suspend fun setTitle(id: Long, title: String) = renameRecipe(id, title)

    /**
     * 原料整体替换。编辑界面是「改完一整份再存」，所以这里不做增量 diff：
     * 行数就几十，整份换掉最不容易写出错，还顺手把 sort_order 重排成 0..n。
     */
    suspend fun setIngredients(recipeId: Long, lines: List<IngredientLine>) = io {
        // 先滤掉空行再编号，否则空行会让 sort_order 跳号（0、2、3…）
        val items = lines.filterNot { it.isBlank }.mapIndexed { index, line ->
            IngredientEntity(
                recipeId = recipeId,
                name = line.name.trim(),
                amount = line.amount.trim(),
                unit = line.unit.trim(),
                sortOrder = index,
            )
        }
        recipeDao.replaceIngredients(recipeId, items)
    }

    /** 做法 / 备注整体替换。 */
    suspend fun setContent(recipeId: Long, section: ContentSection, lines: List<ContentLine>) = io {
        val cleaned = ContentOutline.normalize(lines)
        recipeDao.replaceContent(
            recipeId = recipeId,
            section = section,
            items = cleaned.mapIndexed { index, line ->
                ContentBlockEntity(
                    recipeId = recipeId,
                    section = section,
                    kind = if (line.isHeading) BlockKind.HEADING else BlockKind.LIST_ITEM,
                    level = line.level,
                    text = line.text,
                    sortOrder = index,
                )
            },
        )
    }

    /** 把食谱的分类整体设成 [categoryIds]（多退少补）。 */
    suspend fun setCategories(recipeId: Long, categoryIds: Set<Long>) = io {
        database.withTransaction {
            val existing = categoryDao.linkedCategoryIds(recipeId).toSet()
            (existing - categoryIds).forEach { categoryDao.unlink(recipeId, it) }
            (categoryIds - existing).forEach { categoryDao.link(RecipeCategoryCrossRef(recipeId, it)) }
            // 改了分类也是改了这道菜：不标脏的话，家人那边永远看不到分类变化
            if (existing != categoryIds) recipeDao.touch(recipeId, System.currentTimeMillis())
        }
    }

    /**
     * 批量设置分类：只加不减。
     * 批量场景下“没勾的就被删掉”太容易误伤，要去掉分类请改进单个食谱。
     */
    suspend fun addCategories(recipeIds: List<Long>, categoryIds: Set<Long>) = io {
        database.withTransaction {
            val now = System.currentTimeMillis()
            recipeIds.forEach { recipeId ->
                val existing = categoryDao.linkedCategoryIds(recipeId).toSet()
                val added = categoryIds - existing
                if (added.isEmpty()) return@forEach
                added.forEach { categoryDao.link(RecipeCategoryCrossRef(recipeId, it)) }
                recipeDao.touch(recipeId, now)
            }
        }
    }

    /**
     * 按名字取分类，没有就建一个。名字在库里是唯一的，
     * 所以撞名（insert 返回 -1）时再查一次拿已有 id。
     */
    suspend fun ensureCategory(name: String): Long = io {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分类名不能为空" }
        categoryDao.findByName(trimmed)?.let { return@io it.id }
        val inserted = categoryDao.insert(
            CategoryEntity(
                uuid = newUuid(),
                name = trimmed,
                sortOrder = categoryDao.maxSortOrder() + 1,
            ),
        )
        if (inserted > 0) inserted else requireNotNull(categoryDao.findByName(trimmed)).id
    }

    // ---------- 图片 ----------

    suspend fun images(recipeId: Long): List<RecipeImageEntity> =
        io { imageDao.listFor(recipeId) }

    /**
     * 把已经落盘的图片挂到食谱上，排到最后。
     * 这是这道菜的第一张图时，顺手把它设成列表封面。
     *
     * 超过 [MAX_RECIPE_IMAGES] 张时返回 null（调用方负责把已经写下的文件删掉）——
     * 上限不能只靠界面上「隐藏加号」来保证，导入备份这条路也得挡住。
     */
    suspend fun addImage(recipeId: Long, imported: ImportedImage): Long? = io {
        val count = imageDao.countFor(recipeId)
        if (count >= MAX_RECIPE_IMAGES) return@io null
        val id = imageDao.insert(
            RecipeImageEntity(
                recipeId = recipeId,
                path = imported.path,
                thumbPath = imported.thumbPath,
                width = imported.width,
                height = imported.height,
                sha256 = imported.sha256,
                thumbSha256 = imported.thumbSha256,
                sortOrder = count,
                isCover = count == 0,
            ),
        )
        recipeDao.touch(recipeId, System.currentTimeMillis())
        id
    }

    /** 删图：先删库里的行，再删磁盘上的两个文件。 */
    suspend fun deleteImage(image: RecipeImageEntity) = io {
        database.withTransaction {
            imageDao.deleteById(image.id)
            renumberImages(image.recipeId, imageDao.listFor(image.recipeId))
            recipeDao.touch(image.recipeId, System.currentTimeMillis())
        }
        deleteFilesIfUnreferenced(image.path, image.thumbPath)
    }

    /**
     * 删图的两个文件，但**先问一句还有没有别的行在用它**。
     *
     * 同步落地的图按内容寻址存（`images/<sha256>.jpg`）：两道菜用同一张图时，磁盘上只有
     * 一份文件。直接删会把另一道菜的图也删掉——它的行还在，于是变成裂图，而且这个哈希
     * 再也传不上去。所以先按路径查一遍引用数，最后一份才删。
     */
    private suspend fun deleteFilesIfUnreferenced(path: String, thumbPath: String) {
        if (path.isNotBlank() && imageDao.countByPath(path) == 0) {
            ImageStore.deleteFiles(baseDir, path, "")
        }
        if (thumbPath.isNotBlank() && thumbPath != path && imageDao.countByPath(thumbPath) == 0) {
            ImageStore.deleteFiles(baseDir, thumbPath, "")
        }
    }

    /** 换顺序：把 [from] 位置的图挪到 [to]。 */
    suspend fun moveImage(recipeId: Long, from: Int, to: Int) = io {
        val ordered = imageDao.listFor(recipeId).toMutableList()
        if (from !in ordered.indices || to !in ordered.indices || from == to) return@io
        ordered.add(to, ordered.removeAt(from))
        renumberImages(recipeId, ordered)
        recipeDao.touch(recipeId, System.currentTimeMillis())
    }

    /** 设为列表封面（一张图独占）。 */
    suspend fun setCover(recipeId: Long, imageId: Long) = io {
        database.withTransaction {
            imageDao.clearCover(recipeId)
            imageDao.markCover(imageId)
            recipeDao.touch(recipeId, System.currentTimeMillis())
        }
    }

    /** 顺序重排成 0..n，顺便保证总有且只有一张封面。 */
    private suspend fun renumberImages(recipeId: Long, ordered: List<RecipeImageEntity>) {
        database.withTransaction {
            ordered.forEachIndexed { index, image ->
                if (image.sortOrder != index) imageDao.updateSortOrder(image.id, index)
            }
            if (ordered.isEmpty()) return@withTransaction
            if (ordered.none { it.isCover }) {
                val first = ordered.first()
                imageDao.clearCover(recipeId)
                imageDao.markCover(first.id)
            }
        }
    }


    // ---------- 同步 ----------
    //
    // 这里只放「读游标 + 写游标 + 打墓碑」这些库内动作；拉取、推送、冲突处理都在
    // SyncEngine 里。放在仓库里是因为界面只认仓库这一个入口，设置页要显示上次同步时间。

    /** 同步游标（库里只有一行）。 */
    val syncState: Flow<SyncStateEntity?> = syncStateDao.observe()

    /** 冲突副本条数：首页顶部据此提示「有几道菜在两边都改过」。 */
    val conflictCopies: Flow<Int> = recipeDao.observeConflictCopies(SyncProtocol.CONFLICT_SUFFIX)

    suspend fun currentSyncState(): SyncStateEntity? = io { syncStateDao.get() }

    suspend fun saveSyncState(state: SyncStateEntity) = io { syncStateDao.put(state) }

    /** 全部标脏：断开设备之后，本地每一道菜都要能重新推给下一个服务端。 */
    suspend fun markAllDirty() = io { recipeDao.markAllDirty() }

    /**
     * 本机标识。服务端用它记录「这条改动是谁写的」，第一次要用的时候才生成。
     *
     * 注意 `put` 是整行替换，所以这里是「读出来改一个字段再写回」，
     * 不会把 lastRev 顺手清零。
     */
    suspend fun deviceId(): String = io {
        val current = syncStateDao.get()
        current?.deviceId?.takeIf { it.isNotBlank() }?.let { return@io it }
        val generated = newUuid()
        syncStateDao.put((current ?: SyncStateEntity()).copy(deviceId = generated))
        generated
    }

    /**
     * 把远端推来的墓碑落到本机（家人删掉了这道菜）。找到并打上墓碑返回 true，本机没有这条返回 false。
     *
     * 和 [deleteRecipes] 的差别是**故意的**：这条路不动本机饮食记录，也不删图片文件。
     * 谁删的谁清理磁盘；而「我上周三吃过这道菜」是本机自己的历史，
     * 不该因为家人删了菜谱就跟着消失。
     */
    suspend fun applyRemoteTombstone(uuid: String, deletedAt: Long): Boolean = io {
        if (deletedAt <= 0) return@io false
        val row = recipeDao.findByUuid(uuid) ?: return@io false
        recipeDao.applyRemoteTombstone(row.id, deletedAt)
        true
    }

    // ---------- 备份 ----------

    /** 现有多少道菜、多少条饮食记录（导入前提示要覆盖掉什么）。 */
    suspend fun counts(): Pair<Int, Int> = io {
        recipeDao.observeCount().first() to mealLogDao.observeCount().first()
    }

    /** 把当前全部数据写成一个备份 zip。 */
    suspend fun exportBackup(out: OutputStream) = io {
        BackupArchive.write(buildBackup(), baseDir, out)
    }

    /**
     * 导入备份：**整库替换**（不做合并）。
     *
     * 图片先解到临时目录，等数据库那边成功写完再换目录——不然中途失败会留下
     * 「库里有图、磁盘上没有」的烂摊子；数据库那边是一个事务，失败就整个回滚。
     */
    suspend fun importBackup(input: InputStream) = io {
        val tmp = File(baseDir, RESTORE_TMP_DIR)
        tmp.deleteRecursively()
        tmp.mkdirs()
        val backup = try {
            BackupArchive.read(input, tmp)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
        try {
            database.withTransaction {
                database.clearAllTables()
                restore(backup, tmp)
            }
            replaceImageDirs(tmp)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
        tmp.deleteRecursively()
    }

    private suspend fun buildBackup(): BackupFile {
        val ingredients = recipeDao.allIngredients().groupBy { it.recipeId }
        val blocks = recipeDao.allContentBlocks().groupBy { it.recipeId }
        val images = recipeDao.allRecipeImages().groupBy { it.recipeId }
        val links = recipeDao.allRecipeCategories().groupBy { it.recipeId }
        return BackupFile.create(
            exportedAt = System.currentTimeMillis(),
            categories = categoryDao.allCategories().map {
                BackupCategory(id = it.id, name = it.name, sortOrder = it.sortOrder, uuid = it.uuid)
            },
            recipes = recipeDao.allRecipes().map { recipe ->
                BackupRecipe(
                    id = recipe.id,
                    title = recipe.title,
                    createdAt = recipe.createdAt,
                    updatedAt = recipe.updatedAt,
                    uuid = recipe.uuid,
                    categories = links[recipe.id].orEmpty().map { it.categoryId },
                    ingredients = ingredients[recipe.id].orEmpty().map {
                        BackupIngredient(it.name, it.amount, it.unit, it.sortOrder)
                    },
                    content = blocks[recipe.id].orEmpty().map {
                        BackupContentBlock(
                            section = it.section.name,
                            kind = it.kind.name,
                            level = it.level,
                            text = it.text,
                            sortOrder = it.sortOrder,
                        )
                    },
                    images = images[recipe.id].orEmpty().map {
                        BackupImage(
                            file = it.path,
                            thumbFile = it.thumbPath,
                            width = it.width,
                            height = it.height,
                            sortOrder = it.sortOrder,
                            isCover = it.isCover,
                            sha256 = it.sha256,
                            thumbSha256 = it.thumbSha256,
                        )
                    },
                )
            },
            mealLogs = mealLogDao.allLogs().map {
                BackupMealLog(
                    recipeId = it.recipeId,
                    epochDay = it.epochDay,
                    slot = it.slot.name,
                    createdAt = it.createdAt,
                )
            },
        )
    }

    /**
     * 按原 id 写回。被引用的东西缺了就丢掉那条引用，而不是让整个导入失败：
     * 备份文件可能是手改过的，或者图片文件在传输中丢了。
     *
     * 身份（uuid）优先用备份里的：从 v1 老备份、或者手写的备份进来时没有 uuid，
     * 那就现生成一个。写回的行一律**标脏**——不管这份备份是哪来的，
     * 服务端上有没有这些行本机并不知道，标脏让它下次同步去认领（服务端按 uuid 去重，多传无害）。
     */
    private suspend fun restore(backup: BackupFile, tmp: File) {
        val recipeIds = backup.recipes.map { it.id }.toSet()
        val categoryIds = backup.categories.map { it.id }.toSet()

        categoryDao.insertAll(
            backup.categories.map {
                CategoryEntity(
                    id = it.id,
                    uuid = it.uuid.ifBlank { newUuid() },
                    name = it.name,
                    sortOrder = it.sortOrder,
                )
            },
        )
        recipeDao.insertRecipes(
            backup.recipes.map {
                RecipeEntity(
                    id = it.id,
                    uuid = it.uuid.ifBlank { newUuid() },
                    title = it.title,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    deletedAt = 0,
                    dirty = true,
                    serverRev = 0,
                )
            },
        )
        recipeDao.insertAllLinks(
            backup.recipes.flatMap { recipe ->
                recipe.categories.filter { it in categoryIds }
                    .map { RecipeCategoryCrossRef(recipe.id, it) }
            },
        )
        recipeDao.insertAllIngredients(
            backup.recipes.flatMap { recipe ->
                recipe.ingredients.map {
                    IngredientEntity(
                        recipeId = recipe.id,
                        name = it.name,
                        amount = it.amount,
                        unit = it.unit,
                        sortOrder = it.sortOrder,
                    )
                }
            },
        )
        recipeDao.insertAllContentBlocks(
            backup.recipes.flatMap { recipe ->
                recipe.content.map {
                    ContentBlockEntity(
                        recipeId = recipe.id,
                        section = it.section.toContentSection(),
                        kind = it.kind.toBlockKind(),
                        level = it.level,
                        text = it.text,
                        sortOrder = it.sortOrder,
                    )
                }
            },
        )
        imageDao.insertAll(
            backup.recipes.flatMap { recipe ->
                recipe.images
                    .asSequence()
                    .filter { safeStoredPath(it.file) && File(tmp, it.file).isFile }
                    .take(MAX_RECIPE_IMAGES)
                    .map {
                        RecipeImageEntity(
                            recipeId = recipe.id,
                            path = it.file,
                            // 缩略图丢了就存空串，读的时候会回退到原图
                            thumbPath = it.thumbFile.takeIf { thumb ->
                                safeStoredPath(thumb) && File(tmp, thumb).isFile
                            }.orEmpty(),
                            width = it.width,
                            height = it.height,
                            // 旧备份里没有哈希（字段默认空串），同步前会惰性补算
                            sha256 = it.sha256,
                            thumbSha256 = it.thumbSha256,
                            sortOrder = it.sortOrder,
                            isCover = it.isCover,
                        )
                    }
                    .toList()
            },
        )
        mealLogDao.insertAllLogs(
            backup.mealLogs.filter { it.recipeId in recipeIds && it.epochDay in 0..MAX_EPOCH_DAY }
                .mapNotNull { log ->
                    log.slot.toMealSlot()?.let {
                        MealLogEntity(
                            epochDay = log.epochDay,
                            slot = it,
                            recipeId = log.recipeId,
                            createdAt = log.createdAt,
                        )
                    }
                },
        )
        // 封面掉了一张（文件丢了）时兜回来：每道菜重排顺序并保证有封面
        backup.recipes.forEach { recipe ->
            val ordered = imageDao.listFor(recipe.id)
            if (ordered.isNotEmpty()) renumberImages(recipe.id, ordered)
        }
    }

    /**
     * 把临时目录里的 images/ 和 thumbs/ 换到正式位置。
     *
     * 先把旧目录改名挪开、把新的 rename 进来，最后才删旧的。**不要**先
     * `deleteRecursively()` 再 rename：rename 失败（磁盘满、目录被占用）时旧图已经没了、
     * 新图还在临时目录里，紧接着调用方删掉临时目录，就只剩「库里有图、磁盘上没有」。
     */
    private fun replaceImageDirs(tmp: File) {
        listOf(ImageStore.IMAGE_DIR, ImageStore.THUMB_DIR).forEach { name ->
            val target = File(baseDir, name)
            val stash = File(baseDir, "$name-old")
            stash.deleteRecursively()
            if (target.exists() && !target.renameTo(stash)) return@forEach
            val from = File(tmp, name)
            if (!from.isDirectory) {
                stash.deleteRecursively()
            } else if (from.renameTo(target)) {
                stash.deleteRecursively()
            } else {
                stash.renameTo(target) // 换不过去就把旧的放回来，至少保住原来那一份
            }
        }
    }

    /**
     * 备份里记的图片路径必须老老实实落在 [baseDir] 里。
     *
     * `File(parent, child)` 在 child 以 `/` 开头时会**忽略 parent**，而 [ImageStore.deleteFiles]
     * 是拿这些路径直接删文件的，所以一个手改过的备份能删到应用私有目录外面去。
     */
    private fun safeStoredPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("..")) return false
        return File(baseDir, path).canonicalPath.startsWith(baseDir.canonicalPath + File.separator)
    }

    private fun String.toContentSection(): ContentSection =
        ContentSection.entries.firstOrNull { it.name == this } ?: ContentSection.DIRECTIONS

    private fun String.toBlockKind(): BlockKind =
        BlockKind.entries.firstOrNull { it.name == this } ?: BlockKind.LIST_ITEM

    private fun String.toMealSlot(): MealSlot? = MealSlot.entries.firstOrNull { it.name == this }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val RESTORE_TMP_DIR = "restore-tmp"

        /** 备份里的 epochDay 只接受这个范围（约到公元 4700 年），挡住乱写的值把日期算崩。 */
        const val MAX_EPOCH_DAY = 1_000_000L
    }
}