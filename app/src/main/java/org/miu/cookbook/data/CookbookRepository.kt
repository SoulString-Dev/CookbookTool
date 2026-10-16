package org.miu.cookbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.miu.cookbook.data.db.BlockKind
import org.miu.cookbook.data.db.CategoryDao
import org.miu.cookbook.data.db.CategoryEntity
import org.miu.cookbook.data.db.ContentBlockEntity
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.IngredientEntity
import org.miu.cookbook.data.db.MealLogDao
import org.miu.cookbook.data.db.RecipeCategoryCrossRef
import org.miu.cookbook.data.db.RecipeDao
import org.miu.cookbook.data.db.RecipeImageDao
import org.miu.cookbook.data.db.RecipeImageEntity
import org.miu.cookbook.data.db.RecipeDetail
import org.miu.cookbook.data.db.RecipeEntity
import org.miu.cookbook.data.db.RecipeListRow
import org.miu.cookbook.data.image.ImageStore
import org.miu.cookbook.data.image.ImportedImage
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.ContentOutline
import org.miu.cookbook.domain.recipe.IngredientLine

/**
 * 界面唯一的数据入口。读直接透传 Room 的 Flow，写统一走 IO 线程。
 *
 * 没有引 Repository 接口 + 实现那一套：只有一个实现，抽接口除了多一层跳转
 * 没有任何好处；测试要替身的话传个假 DAO 就够了。
 */
class CookbookRepository(
    private val recipeDao: RecipeDao,
    private val categoryDao: CategoryDao,
    private val mealLogDao: MealLogDao,
    private val imageDao: RecipeImageDao,
    /** 图片存放的根目录，一般就是 `filesDir`。 */
    private val baseDir: java.io.File,
) {

    // ---------- 读 ----------

    /**
     * 这些都是**属性而不是函数**：每次调用 DAO 的 `observeXxx()` 都会新建一个
     * Flow 对象，而 Compose 的 `collectAsStateWithLifecycle` 拿 Flow 实例当 key，
     * 写成函数的话每次重组都会重新订阅一遍。
     */
    val recipeList: Flow<List<RecipeListRow>> = recipeDao.observeAllWithDetails()

    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun recipeDetail(id: Long): Flow<RecipeDetail?> = recipeDao.observeDetail(id)

    suspend fun linkedCategoryIds(recipeId: Long): Set<Long> =
        io { categoryDao.linkedCategoryIds(recipeId).toSet() }

    // ---------- 写 ----------

    /** 新建食谱，返回新食谱 id。 */
    suspend fun createRecipe(title: String): Long = io {
        val now = System.currentTimeMillis()
        recipeDao.insert(RecipeEntity(title = title.trim(), createdAt = now, updatedAt = now))
    }

    /** 改名。空名字直接忽略——界面上保存按钮会拦住，这里再兜一层。 */
    suspend fun renameRecipe(id: Long, title: String) = io {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@io
        val existing = recipeDao.findById(id) ?: return@io
        if (existing.title == trimmed) return@io
        recipeDao.update(existing.copy(title = trimmed, updatedAt = System.currentTimeMillis()))
    }

    /** 批量删除食谱。饮食历史由外键 CASCADE 一并删除。 */
    suspend fun deleteRecipes(ids: List<Long>) = io {
        if (ids.isNotEmpty()) recipeDao.deleteByIds(ids)
    }

    /** 这些食谱下共有多少条饮食记录，用于删除前的提示。 */
    suspend fun countMealLogs(recipeIds: List<Long>): Int = io {
        if (recipeIds.isEmpty()) 0 else mealLogDao.countForRecipes(recipeIds)
    }

    /** 改名（详情页编辑态用）。 */
    suspend fun setTitle(id: Long, title: String) = renameRecipe(id, title)

    /**
     * 原料整体替换。编辑界面是「改完一整份再存」，所以这里不做增量 diff：
     * 行数就几十，整份换掉最不容易写出错，还顺手把 sort_order 重排成 0..n。
     */
    suspend fun setIngredients(recipeId: Long, lines: List<IngredientLine>) = io {
        val items = lines.mapIndexedNotNull { index, line ->
            if (line.isBlank) {
                null
            } else {
                IngredientEntity(
                    recipeId = recipeId,
                    name = line.name.trim(),
                    amount = line.amount.trim(),
                    unit = line.unit.trim(),
                    sortOrder = index,
                )
            }
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
        val existing = categoryDao.linkedCategoryIds(recipeId).toSet()
        (existing - categoryIds).forEach { categoryDao.unlink(recipeId, it) }
        (categoryIds - existing).forEach { categoryDao.link(RecipeCategoryCrossRef(recipeId, it)) }
    }

    /**
     * 批量设置分类：只加不减。
     * 批量场景下“没勾的就被删掉”太容易误伤，要去掉分类请改进单个食谱。
     */
    suspend fun addCategories(recipeIds: List<Long>, categoryIds: Set<Long>) = io {
        recipeIds.forEach { recipeId ->
            val existing = categoryDao.linkedCategoryIds(recipeId).toSet()
            (categoryIds - existing).forEach { categoryDao.link(RecipeCategoryCrossRef(recipeId, it)) }
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
            CategoryEntity(name = trimmed, sortOrder = categoryDao.maxSortOrder() + 1),
        )
        if (inserted > 0) inserted else requireNotNull(categoryDao.findByName(trimmed)).id
    }

    // ---------- 图片 ----------

    suspend fun images(recipeId: Long): List<RecipeImageEntity> =
        io { imageDao.listFor(recipeId) }

    /**
     * 把已经落盘的图片挂到食谱上，排到最后。
     * 这是这道菜的第一张图时，顺手把它设成列表封面。
     */
    suspend fun addImage(recipeId: Long, imported: ImportedImage): Long = io {
        val isFirst = imageDao.countFor(recipeId) == 0
        val id = imageDao.insert(
            RecipeImageEntity(
                recipeId = recipeId,
                path = imported.path,
                thumbPath = imported.thumbPath,
                width = imported.width,
                height = imported.height,
                sortOrder = imageDao.countFor(recipeId),
                isCover = isFirst,
            ),
        )
        recipeDao.touch(recipeId, System.currentTimeMillis())
        id
    }

    /** 删图：先删库里的行，再删磁盘上的两个文件。 */
    suspend fun deleteImage(image: RecipeImageEntity) = io {
        imageDao.deleteById(image.id)
        ImageStore.deleteFiles(baseDir, image.path, image.thumbPath)
        renumberImages(image.recipeId, imageDao.listFor(image.recipeId))
        recipeDao.touch(image.recipeId, System.currentTimeMillis())
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
        imageDao.clearCover(recipeId)
        imageDao.markCover(imageId)
        recipeDao.touch(recipeId, System.currentTimeMillis())
    }

    /** 顺序重排成 0..n，顺便保证总有且只有一张封面。 */
    private suspend fun renumberImages(recipeId: Long, ordered: List<RecipeImageEntity>) {
        ordered.forEachIndexed { index, image ->
            if (image.sortOrder != index) imageDao.updateSortOrder(image.id, index)
        }
        if (ordered.isEmpty()) return
        if (ordered.none { it.isCover }) {
            val first = ordered.first()
            imageDao.clearCover(recipeId)
            imageDao.markCover(first.id)
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}