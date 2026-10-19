package org.miu.cookbook.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.miu.cookbook.domain.meal.MealSlot

@Dao
abstract class RecipeDao {

    // 读的那一侧**每条查询都要自己带 `deleted_at = 0`**（soft delete 之后没有外键级联兜底了）：
    // 漏掉一处的表现是「删掉的菜还在列表里」，而这种 bug 只有肉眼能发现。

    @Query("SELECT * FROM recipe WHERE deleted_at = 0 ORDER BY created_at DESC, id DESC")
    abstract fun observeAll(): Flow<List<RecipeEntity>>

    /** 列表页要用的全部信息（菜名/分类/原料/封面）。 */
    @Transaction
    @Query("SELECT * FROM recipe WHERE deleted_at = 0 ORDER BY created_at DESC, id DESC")
    abstract fun observeAllWithDetails(): Flow<List<RecipeListRow>>

    @Query("SELECT * FROM recipe WHERE id = :id AND deleted_at = 0")
    abstract fun observeById(id: Long): Flow<RecipeEntity?>

    /** 详情页要用的全部信息（多了正文）。 */
    @Transaction
    @Query("SELECT * FROM recipe WHERE id = :id AND deleted_at = 0")
    abstract fun observeDetail(id: Long): Flow<RecipeDetail?>

    @Query("SELECT * FROM recipe WHERE id = :id AND deleted_at = 0")
    abstract suspend fun findById(id: Long): RecipeEntity?

    /** 按跨设备身份找，**故意不过滤墓碑**：把远端的删除落到本地时，这正是要找到那一行。 */
    @Query("SELECT * FROM recipe WHERE uuid = :uuid LIMIT 1")
    abstract suspend fun findByUuid(uuid: String): RecipeEntity?

    @Query("SELECT COUNT(*) FROM recipe WHERE deleted_at = 0")
    abstract fun observeCount(): Flow<Int>

    @Insert
    abstract suspend fun insert(recipe: RecipeEntity): Long

    @Update
    abstract suspend fun update(recipe: RecipeEntity)

    /** 软删：打墓碑 + 标脏。行必须留着，「这行被删了」这件事才同步得出去。 */
    @Query("UPDATE recipe SET deleted_at = :now, updated_at = :now, dirty = 1 WHERE id IN (:ids)")
    abstract suspend fun softDeleteByIds(ids: List<Long>, now: Long)

    /**
     * 清掉过期的墓碑。饮食记录会被外键级联带走——
     * 这是已知取舍：meal_log 靠 `recipe_id` 挂在 recipe 上，要彻底留住本机历史，
     * 得把 meal_log 改成按 uuid 弱引用，那是另一件事（见 README 的已知边界）。
     */
    @Query("DELETE FROM recipe WHERE deleted_at != 0 AND deleted_at < :before")
    abstract suspend fun purgeTombstones(before: Long): Int

    @Query("UPDATE recipe SET title = :title, updated_at = :updatedAt, dirty = 1 WHERE id = :id")
    abstract suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    /** 标脏（同步引擎重试、以及分类变更这种「内容没改但关联改了」的场合要用）。 */
    @Query("UPDATE recipe SET dirty = 1 WHERE id IN (:ids)")
    abstract suspend fun markDirty(ids: List<Long>)

    /**
     * 把**远端**的墓碑落到本机：只打墓碑、不顺带改别的。
     *
     * 特意和 [softDeleteByIds] 分开：[softDeleteByIds] 是「我这台机器上删掉这道菜」，
     * 本机的饮食记录跟着走；这里是「家人删掉了这道菜」，本机的饮食记录、图片文件都不动——
     * 记录是自己吃过的历史，不该因为别人删了菜谱就消失。
     */
    @Query("UPDATE recipe SET deleted_at = :deletedAt, updated_at = :deletedAt, dirty = 0 WHERE id = :id")
    abstract suspend fun applyRemoteTombstone(id: Long, deletedAt: Long)

    // ---------- 原料 / 做法备注：整体替换 ----------
    //
    // 编辑界面是「改完一整份再存」，所以这里就按整份替换写：
    // 先删后插，全在一个事务里，中途崩了也不会留半份数据。

    @Query("DELETE FROM ingredient WHERE recipe_id = :recipeId")
    abstract suspend fun deleteIngredientsFor(recipeId: Long)

    @Query("DELETE FROM content_block WHERE recipe_id = :recipeId AND section = :section")
    abstract suspend fun deleteContentFor(recipeId: Long, section: ContentSection)

    @Insert
    abstract suspend fun insertIngredients(items: List<IngredientEntity>)

    @Insert
    abstract suspend fun insertContentBlocks(items: List<ContentBlockEntity>)

    @Transaction
    open suspend fun replaceIngredients(recipeId: Long, items: List<IngredientEntity>) {
        deleteIngredientsFor(recipeId)
        if (items.isNotEmpty()) insertIngredients(items)
        touch(recipeId, System.currentTimeMillis())
    }

    @Transaction
    open suspend fun replaceContent(
        recipeId: Long,
        section: ContentSection,
        items: List<ContentBlockEntity>,
    ) {
        deleteContentFor(recipeId, section)
        if (items.isNotEmpty()) insertContentBlocks(items)
        touch(recipeId, System.currentTimeMillis())
    }

    @Query("UPDATE recipe SET updated_at = :updatedAt, dirty = 1 WHERE id = :id")
    abstract suspend fun touch(id: Long, updatedAt: Long)

    // ---------- 备份：整份读出 / 整份写回 ----------
    //
    // 导入是「清空 + 按原 id 写回」，所以这些插入都用 REPLACE：
    // id 保持原样，食谱-分类-图片-饮食记录之间的引用才不会错位。

    /** 导出只带活着的：墓碑是同步用的中间状态，不该出现在给用户看的备份文件里。 */
    @Query("SELECT * FROM recipe WHERE deleted_at = 0 ORDER BY id")
    abstract suspend fun allRecipes(): List<RecipeEntity>

    @Query("SELECT * FROM ingredient ORDER BY recipe_id, sort_order, id")
    abstract suspend fun allIngredients(): List<IngredientEntity>

    @Query("SELECT * FROM content_block ORDER BY recipe_id, section, sort_order, id")
    abstract suspend fun allContentBlocks(): List<ContentBlockEntity>

    @Query("SELECT * FROM recipe_category")
    abstract suspend fun allRecipeCategories(): List<RecipeCategoryCrossRef>

    @Query("SELECT * FROM recipe_image ORDER BY recipe_id, sort_order, id")
    abstract suspend fun allRecipeImages(): List<RecipeImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRecipes(items: List<RecipeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllIngredients(items: List<IngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllContentBlocks(items: List<ContentBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllLinks(items: List<RecipeCategoryCrossRef>)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM category ORDER BY sort_order, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    /** 按跨设备身份认分类。名字是给人改的，uuid 才是身份。 */
    @Query("SELECT * FROM category WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM category")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: RecipeCategoryCrossRef)

    @Query("DELETE FROM recipe_category WHERE recipe_id = :recipeId AND category_id = :categoryId")
    suspend fun unlink(recipeId: Long, categoryId: Long)

    @Query("SELECT category_id FROM recipe_category WHERE recipe_id = :recipeId")
    suspend fun linkedCategoryIds(recipeId: Long): List<Long>

    @Query("SELECT * FROM category ORDER BY sort_order, id")
    suspend fun allCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CategoryEntity>)
}

@Dao
abstract class MealLogDao {

    @Query("SELECT * FROM meal_log ORDER BY epoch_day DESC, created_at DESC")
    abstract fun observeAll(): Flow<List<MealLogEntity>>

    /**
     * 历史页用：一条记录带上菜名和封面路径。
     *
     * 用 JOIN + 子查询一次拿齐，而不是 @Relation（那会是 N+1 次查询）。
     * 注意 **不要** 在 SQL 里按 slot 排序：枚举存的是名字（TEXT），
     * 按字母排出来是 BREAKFAST/DINNER/LUNCH，早中晚的顺序是错的，交给 Kotlin 按 ordinal 排。
     */
    @Query(
        """
        SELECT m.id AS logId, m.recipe_id AS recipeId, m.epoch_day AS epochDay, m.slot AS slot,
               m.created_at AS createdAt, r.title AS title,
               (SELECT i.path FROM recipe_image i WHERE i.recipe_id = m.recipe_id
                 ORDER BY i.is_cover DESC, i.sort_order, i.id LIMIT 1) AS coverPath
        FROM meal_log m
        JOIN recipe r ON r.id = m.recipe_id
        ORDER BY m.epoch_day ASC, m.created_at ASC, m.id ASC
        """,
    )
    abstract fun observeHistory(): Flow<List<MealLogRow>>

    /** 某道菜自己的饮食历史（详情页那个浮窗），按时间倒序。 */
    @Query(
        """
        SELECT m.id AS logId, m.recipe_id AS recipeId, m.epoch_day AS epochDay, m.slot AS slot,
               m.created_at AS createdAt, r.title AS title,
               (SELECT i.path FROM recipe_image i WHERE i.recipe_id = m.recipe_id
                 ORDER BY i.is_cover DESC, i.sort_order, i.id LIMIT 1) AS coverPath
        FROM meal_log m
        JOIN recipe r ON r.id = m.recipe_id
        WHERE m.recipe_id = :recipeId
        ORDER BY m.epoch_day ASC, m.created_at ASC, m.id ASC
        """,
    )
    abstract fun observeForRecipe(recipeId: Long): Flow<List<MealLogRow>>

    @Query("SELECT COUNT(*) FROM meal_log")
    abstract fun observeCount(): Flow<Int>

    @Insert
    abstract suspend fun insert(log: MealLogEntity): Long

    @Query("DELETE FROM meal_log WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM meal_log WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    /** 删食谱时一起清掉它的饮食记录（本机删除的语义，和 v1 的级联效果一致）。 */
    @Query("DELETE FROM meal_log WHERE recipe_id IN (:recipeIds)")
    abstract suspend fun deleteForRecipes(recipeIds: List<Long>)

    @Query("DELETE FROM meal_log WHERE epoch_day = :day")
    abstract suspend fun deleteForDay(day: Long)

    @Query("SELECT COUNT(*) FROM meal_log WHERE epoch_day = :day")
    abstract suspend fun countForDay(day: Long): Int

    @Query("SELECT * FROM meal_log WHERE epoch_day = :day AND slot = :slot ORDER BY created_at, id")
    abstract suspend fun forSlot(day: Long, slot: MealSlot): List<MealLogEntity>

    @Query("SELECT COUNT(*) FROM meal_log WHERE recipe_id = :recipeId")
    abstract suspend fun countForRecipe(recipeId: Long): Int

    /** 删除食谱前提示「会一并删掉 N 条饮食记录」用。 */
    @Query("SELECT COUNT(*) FROM meal_log WHERE recipe_id IN (:recipeIds)")
    abstract suspend fun countForRecipes(recipeIds: List<Long>): Int

    /**
     * 把某天的一餐整体挪到**任意一天的任意一餐**（记错日期是最常见的手滑）。
     *
     * 一条 UPDATE 就够了，不需要先查再写。
     */
    @Query("UPDATE meal_log SET epoch_day = :toDay, slot = :toSlot WHERE epoch_day = :fromDay AND slot = :fromSlot")
    abstract suspend fun moveSlotTo(
        fromDay: Long,
        fromSlot: MealSlot,
        toDay: Long,
        toSlot: MealSlot,
    ): Int

    /**
     * 改到另一餐：**目标那边有记录就整体交换**，没有就直接挪过去。返回是否发生了交换。
     *
     * 交换不能两条 UPDATE 连着写：第一条把源那餐挪过去之后，两条记录就混在同一餐里，
     * 第二条会把它们一起挪回来。所以先借一个现实中不可能出现的日子当中转站。
     */
    @Transaction
    open suspend fun moveOrSwapSlots(
        fromDay: Long,
        fromSlot: MealSlot,
        toDay: Long,
        toSlot: MealSlot,
    ): Boolean {
        if (fromDay == toDay && fromSlot == toSlot) return false
        if (forSlot(toDay, toSlot).isEmpty()) {
            moveSlotTo(fromDay, fromSlot, toDay, toSlot)
            return false
        }
        moveSlotTo(toDay, toSlot, SWAP_TEMP_DAY, toSlot)
        moveSlotTo(fromDay, fromSlot, toDay, toSlot)
        moveSlotTo(SWAP_TEMP_DAY, toSlot, fromDay, fromSlot)
        return true
    }

    /** TODO(未来扩展)：做“按上次吃的时间加权随机”时要用，现在还没有调用点。 */
    @Query("SELECT recipe_id, MAX(epoch_day) AS last_day FROM meal_log GROUP BY recipe_id")
    abstract suspend fun lastCookedDays(): List<RecipeLastCooked>

    @Query("SELECT * FROM meal_log ORDER BY id")
    abstract suspend fun allLogs(): List<MealLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllLogs(items: List<MealLogEntity>)
}

data class RecipeLastCooked(
    @androidx.room.ColumnInfo(name = "recipe_id") val recipeId: Long,
    @androidx.room.ColumnInfo(name = "last_day") val lastDay: Long,
)

@Dao
interface RecipeImageDao {

    @Query("SELECT * FROM recipe_image WHERE recipe_id = :recipeId ORDER BY sort_order, id")
    suspend fun listFor(recipeId: Long): List<RecipeImageEntity>

    @Query("SELECT COUNT(*) FROM recipe_image WHERE recipe_id = :recipeId")
    suspend fun countFor(recipeId: Long): Int

    @Insert
    suspend fun insert(image: RecipeImageEntity): Long

    @Query("DELETE FROM recipe_image WHERE id = :imageId")
    suspend fun deleteById(imageId: Long)

    /** 删食谱时一起清掉图片行（磁盘文件由仓库层负责删）。 */
    @Query("DELETE FROM recipe_image WHERE recipe_id IN (:recipeIds)")
    suspend fun deleteForRecipes(recipeIds: List<Long>)

    @Query("UPDATE recipe_image SET sort_order = :sortOrder WHERE id = :imageId")
    suspend fun updateSortOrder(imageId: Long, sortOrder: Int)

    @Query("UPDATE recipe_image SET is_cover = 0 WHERE recipe_id = :recipeId")
    suspend fun clearCover(recipeId: Long)

    @Query("UPDATE recipe_image SET is_cover = 1 WHERE id = :imageId")
    suspend fun markCover(imageId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecipeImageEntity>)
}

@Dao
interface SyncStateDao {

    /** 只有一行（id = [SyncStateEntity.SINGLETON_ID]），所以用 `WHERE id = 1` 而不是 LIMIT 1。 */
    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    /**
     * 整行写入。故意不做「只 UPDATE 不 INSERT」的窄接口：
     * 那种接口在行还不存在时是静默地什么都没改，游标丢了要过很久才被发现。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)
}

/** 交换两餐时的中转日子：真实日期是 1970 起的天数，永远不可能是 Long.MIN_VALUE。 */
private const val SWAP_TEMP_DAY = Long.MIN_VALUE
