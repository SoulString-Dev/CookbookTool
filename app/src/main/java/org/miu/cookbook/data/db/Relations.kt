package org.miu.cookbook.data.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import org.miu.cookbook.domain.meal.MealSlot

/**
 * 查询结果的组合体。Room 会让 `@Relation` 各查一次再拼起来，
 * 所以列表页和详情页分成两个类：列表不需要正文，详情需要。
 */

/** 列表页：菜名、分类、原料摘要、封面图。 */
data class RecipeListRow(
    @Embedded val recipe: RecipeEntity,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val ingredients: List<IngredientEntity>,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val images: List<RecipeImageEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecipeCategoryCrossRef::class,
            parentColumn = "recipe_id",
            entityColumn = "category_id",
        ),
    )
    val categories: List<CategoryEntity>,
)

/** 详情页：比列表多一份正文（做法/备注的有序列表项）。 */
data class RecipeDetail(
    @Embedded val recipe: RecipeEntity,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val ingredients: List<IngredientEntity>,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val images: List<RecipeImageEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecipeCategoryCrossRef::class,
            parentColumn = "recipe_id",
            entityColumn = "category_id",
        ),
    )
    val categories: List<CategoryEntity>,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val contentBlocks: List<ContentBlockEntity>,
)

/**
 * 历史列表的一行：一条饮食记录 + 那道菜的菜名和封面。
 *
 * 这是个普通 POJO（不是 @Relation 组合体）：历史页一次要显示几十行，
 * 一行一次额外查询不如 JOIN 一次拿齐。字段名靠 SQL 里的 `AS` 对齐。
 */
data class MealLogRow(
    val logId: Long,
    val recipeId: Long,
    val epochDay: Long,
    val slot: MealSlot,
    val createdAt: Long,
    val title: String,
    /** 相对 filesDir 的封面路径，可能没有。 */
    val coverPath: String?,
)
