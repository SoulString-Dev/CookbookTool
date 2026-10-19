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

@Entity(
    tableName = "recipe",
    indices = [Index(value = ["uuid"], unique = true)],
)
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 跨设备身份。[id] 是本机的自增号，出不了这台机器；同步、冲突、图片归属全靠 uuid。
     *
     * **故意不给默认值**：给了 `= ""` 的话，忘记传的那个调用点会带着空 uuid 落库，
     * 而 uuid 上有唯一索引——第二条这样的行会直接插不进去（错误还出现在很远的地方）。
     * 新建一律 `newUuid()`；导入备份时用备份里带的那个，没有才现生成。
     */
    @ColumnInfo(defaultValue = "") val uuid: String,
    /** 菜名。Markdown 里的 H1 由它生成，不把 H1 存进正文。 */
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 删除墓碑：0 = 活着，非 0 = 删除时刻。软删而非物理删，删除这件事才同步得出去。 */
    @ColumnInfo(name = "deleted_at", defaultValue = "0") val deletedAt: Long = 0,
    /**
     * 待上传标记。默认 **true**（不是 false）是故意的：忘记显式设置时宁可多传一次
     * （服务端按 uuid 去重，多传无害），也比悄悄不上传、数据永远留在这台机器上强。
     * 从服务端落地时显式写 false。
     */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
    /** 最后见到的服务端版本号。用来判断「本地改动是建立在哪个版本上」，冲突检测要用。 */
    @ColumnInfo(name = "server_rev", defaultValue = "0") val serverRev: Long = 0,
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
    indices = [Index(value = ["name"], unique = true), Index(value = ["uuid"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 跨设备身份，理由同 [RecipeEntity.uuid]。
     *
     * 分类的名字在库里是唯一的，而两台设备各自建的「家常菜」是两个不同的 uuid；
     * 同步落地时**先按 uuid 认，认不到再按名字认**（见同步引擎），
     * 所以这里不能只靠名字，也不能只靠 uuid。
     */
    @ColumnInfo(defaultValue = "") val uuid: String,
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

/**
 * 同步的本地游标：只有一行（[SINGLETON_ID]）。
 *
 * 配对用的**令牌不在这里**，它在 `EncryptedSharedPreferences` 里。原因是
 * 「导入备份」走的是 `clearAllTables()`，会把这张表一起清空——令牌要是也在这儿，
 * 一次「导出再导入」就会把配对关系弄丢，用户得重新输一次口令。
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** 本机标识，首次同步时生成。服务端用它记录「这条改动是谁写的」。 */
    @ColumnInfo(name = "device_id") val deviceId: String = "",
    /** 服务端地址，形如 `https://cookbook.example.com`。 */
    @ColumnInfo(name = "server_url") val serverUrl: String = "",
    /** 已经拉到哪个版本号。只增不减。 */
    @ColumnInfo(name = "last_rev") val lastRev: Long = 0,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long = 0,
    /** 上次失败的原因，显示在设置页上（成功时清空）。 */
    @ColumnInfo(name = "last_error") val lastError: String = "",
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
