package org.miu.cookbook.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.miu.cookbook.domain.meal.MealSlot

/**
 * 数据层的总原则：**结构化字段才是真相，Markdown 只是导出/展示时的序列化结果**。
 * 所以原料、做法、备注都是结构化的行，而不是一坨 Markdown 文本。
 */

@Entity(tableName = "recipe")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 菜名。Markdown 里的 H1 由它生成，不把 H1 存进正文。 */
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 做法 / 备注两个区块共用一张表，用 section 区分。 */
enum class ContentSection { DIRECTIONS, NOTE }

/** 一行是普通的有序列表项，还是被“句号”转出来的三级标题。 */
enum class BlockKind { LIST_ITEM, HEADING }

@Entity(
    tableName = "content_block",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipe_id")],
)
data class ContentBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    val section: ContentSection,
    val kind: BlockKind = BlockKind.LIST_ITEM,
    /** 缩进层级：0 一级有序列表，1 子列表，2 子子列表……仅对 LIST_ITEM 有意义。 */
    val level: Int = 0,
    val text: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "ingredient",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipe_id")],
)
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    /** 原料名 */
    val name: String,
    /** 食材数。故意用文本：允许“适量”“少许”“半”这类写法。 */
    val amount: String = "",
    /** 量词，可为空 */
    val unit: String = "",
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "category",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

@Entity(
    tableName = "recipe_category",
    primaryKeys = ["recipe_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("category_id")],
)
data class RecipeCategoryCrossRef(
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
)

@Entity(
    tableName = "recipe_image",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipe_id")],
)
data class RecipeImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    /** 相对 filesDir 的路径，绝不存绝对路径（应用目录会变） */
    val path: String,
    /** 列表用小图路径 */
    @ColumnInfo(name = "thumb_path") val thumbPath: String,
    val width: Int = 0,
    val height: Int = 0,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    /** 列表封面。默认第一张为封面，但设了封面之后仍可自由排序。 */
    @ColumnInfo(name = "is_cover") val isCover: Boolean = false,
)

@Entity(
    tableName = "meal_log",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipe_id"), Index(value = ["epoch_day", "slot"])],
)
data class MealLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 以 1970-01-01 为起点的天数，跨时区/夏令时都稳。 */
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    val slot: MealSlot,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    /** 记录时刻。界面只显示到餐段，但未来按“上次吃的时间”算权重会用到。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
