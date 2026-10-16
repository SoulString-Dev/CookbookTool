package org.miu.cookbook.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecipeEntity::class,
        ContentBlockEntity::class,
        IngredientEntity::class,
        CategoryEntity::class,
        RecipeCategoryCrossRef::class,
        RecipeImageEntity::class,
        MealLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recipeDao(): RecipeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun mealLogDao(): MealLogDao

    companion object {
        const val NAME = "cookbook.db"

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
            // Room 默认开启外键约束：删食谱会级联清掉它的原料/图片/做法/饮食记录
            .build()
    }
}
