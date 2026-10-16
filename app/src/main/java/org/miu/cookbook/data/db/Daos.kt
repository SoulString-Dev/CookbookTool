package org.miu.cookbook.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {

    @Query("SELECT * FROM recipe ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipe WHERE id = :id")
    fun observeById(id: Long): Flow<RecipeEntity?>

    @Query("SELECT COUNT(*) FROM recipe")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(recipe: RecipeEntity): Long

    @Update
    suspend fun update(recipe: RecipeEntity)

    @Query("DELETE FROM recipe WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM category ORDER BY sort_order, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: RecipeCategoryCrossRef)

    @Query("DELETE FROM recipe_category WHERE recipe_id = :recipeId AND category_id = :categoryId")
    suspend fun unlink(recipeId: Long, categoryId: Long)
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

    /** 未来做“按上次吃的时间加权随机”要用：每道菜最后一次被吃的日子。 */
    @Query("SELECT recipe_id, MAX(epoch_day) AS last_day FROM meal_log GROUP BY recipe_id")
    suspend fun lastCookedDays(): List<RecipeLastCooked>
}

data class RecipeLastCooked(
    @androidx.room.ColumnInfo(name = "recipe_id") val recipeId: Long,
    @androidx.room.ColumnInfo(name = "last_day") val lastDay: Long,
)
