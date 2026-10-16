package org.miu.cookbook.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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
}

@Dao
interface MealLogDao {

    @Query("SELECT * FROM meal_log ORDER BY epoch_day DESC, slot DESC, created_at DESC")
    fun observeAll(): Flow<List<MealLogEntity>>

    @Query("SELECT COUNT(*) FROM meal_log")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(log: MealLogEntity): Long

    @Query("DELETE FROM meal_log WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM meal_log WHERE recipe_id = :recipeId")
    suspend fun countForRecipe(recipeId: Long): Int

    /** 删除食谱前提示「会一并删掉 N 条饮食记录」用。 */
    @Query("SELECT COUNT(*) FROM meal_log WHERE recipe_id IN (:recipeIds)")
    suspend fun countForRecipes(recipeIds: List<Long>): Int

    /** 未来做“按上次吃的时间加权随机”要用：每道菜最后一次被吃的日子。 */
    @Query("SELECT recipe_id, MAX(epoch_day) AS last_day FROM meal_log GROUP BY recipe_id")
    suspend fun lastCookedDays(): List<RecipeLastCooked>
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
}
