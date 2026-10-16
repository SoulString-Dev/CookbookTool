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

    @Query("SELECT * FROM recipe ORDER BY created_at DESC, id DESC")
    abstract fun observeAll(): Flow<List<RecipeEntity>>

    /** 列表页要用的全部信息（菜名/分类/原料/封面）。 */
    @Transaction
    @Query("SELECT * FROM recipe ORDER BY created_at DESC, id DESC")
    abstract fun observeAllWithDetails(): Flow<List<RecipeListRow>>

    @Query("SELECT * FROM recipe WHERE id = :id")
    abstract fun observeById(id: Long): Flow<RecipeEntity?>

    /** 详情页要用的全部信息（多了正文）。 */
    @Transaction
    @Query("SELECT * FROM recipe WHERE id = :id")
    abstract fun observeDetail(id: Long): Flow<RecipeDetail?>

    @Query("SELECT * FROM recipe WHERE id = :id")
    abstract suspend fun findById(id: Long): RecipeEntity?

    @Query("SELECT COUNT(*) FROM recipe")
    abstract fun observeCount(): Flow<Int>

    @Insert
    abstract suspend fun insert(recipe: RecipeEntity): Long

    @Update
    abstract suspend fun update(recipe: RecipeEntity)

    @Query("DELETE FROM recipe WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** 批量删除；饮食历史靠外键级联一起删掉。 */
    @Query("DELETE FROM recipe WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE recipe SET title = :title, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

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

    @Query("UPDATE recipe SET updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun touch(id: Long, updatedAt: Long)

    // ---------- 备份：整份读出 / 整份写回 ----------
    //
    // 导入是「清空 + 按原 id 写回」，所以这些插入都用 REPLACE：
    // id 保持原样，食谱-分类-图片-饮食记录之间的引用才不会错位。

    @Query("SELECT * FROM recipe ORDER BY id")
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
        ORDER BY m.epoch_day DESC, m.created_at DESC, m.id DESC
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

    @Query("DELETE FROM meal_log WHERE epoch_day = :day")
    abstract suspend fun deleteForDay(day: Long)

    @Query("SELECT COUNT(*) FROM meal_log WHERE epoch_day = :day")
    abstract suspend fun countForDay(day: Long): Int

    @Query("SELECT * FROM meal_log WHERE epoch_day = :day AND slot = :slot ORDER BY created_at, id")
    abstract suspend fun forSlot(day: Long, slot: MealSlot): List<MealLogEntity>

    @Query("UPDATE meal_log SET slot = :slot WHERE id = :id")
    abstract suspend fun updateSlot(id: Long, slot: MealSlot)

    @Query("SELECT COUNT(*) FROM meal_log WHERE recipe_id = :recipeId")
    abstract suspend fun countForRecipe(recipeId: Long): Int

    /** 删除食谱前提示「会一并删掉 N 条饮食记录」用。 */
    @Query("SELECT COUNT(*) FROM meal_log WHERE recipe_id IN (:recipeIds)")
    abstract suspend fun countForRecipes(recipeIds: List<Long>): Int

    /**
     * 把某天的一餐整体挪到另一餐；目标那餐已经有记录时**交换**（需求里就是这么定的），
     * 这样不会出现两餐内容叠在一起、也不会丢记录。
     */
    @Transaction
    open suspend fun moveSlot(day: Long, from: MealSlot, to: MealSlot) {
        if (from == to) return
        val sources = forSlot(day, from)
        val targets = forSlot(day, to)
        targets.forEach { updateSlot(it.id, from) }
        sources.forEach { updateSlot(it.id, to) }
    }

    /** 未来做“按上次吃的时间加权随机”要用：每道菜最后一次被吃的日子。 */
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

    @Query("UPDATE recipe_image SET sort_order = :sortOrder WHERE id = :imageId")
    suspend fun updateSortOrder(imageId: Long, sortOrder: Int)

    @Query("UPDATE recipe_image SET is_cover = 0 WHERE recipe_id = :recipeId")
    suspend fun clearCover(recipeId: Long)

    @Query("UPDATE recipe_image SET is_cover = 1 WHERE id = :imageId")
    suspend fun markCover(imageId: Long)

    @Query(
        "UPDATE recipe_image SET thumb_path = :thumbPath WHERE recipe_id = :recipeId AND path = :path",
    )
    suspend fun updateThumbPath(recipeId: Long, path: String, thumbPath: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecipeImageEntity>)
}
