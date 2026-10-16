package org.miu.cookbook.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 备份文件的格式（JSON）。
 *
 * 设计上把「结构」和「图片」分成两层：[BackupFile] 只描述数据，图片用文件名引用，
 * 真正的像素放在 zip 里的 `images/` 与 `thumbs/`。这样备份文件本身是可读的 JSON，
 * 出问题时能直接用文本编辑器打开看，而不是一坨二进制。
 *
 * **主键（id）原样保留**：导入时按这些 id 重新插入，食谱-分类-图片-饮食记录之间的
 * 引用关系才不会错位。
 */
@Serializable
data class BackupFile(
    /**
     * 这两个字段**故意不给默认值**：给了默认值的话，随便一个 JSON 对象（哪怕是别的应用
     * 导出的）都能解析成功——缺字段会落回默认值，看起来就像是本应用的备份。
     */
    val format: String,
    val version: Int,
    val exportedAt: Long = 0,
    val recipes: List<BackupRecipe> = emptyList(),
    val categories: List<BackupCategory> = emptyList(),
    val mealLogs: List<BackupMealLog> = emptyList(),
) {
    companion object {
        const val FORMAT = "cookbook-backup"
        const val VERSION = 1

        /** 构造一份「当前格式」的备份，省得每次都要写 format/version 两个字段。 */
        fun create(
            exportedAt: Long = 0,
            recipes: List<BackupRecipe> = emptyList(),
            categories: List<BackupCategory> = emptyList(),
            mealLogs: List<BackupMealLog> = emptyList(),
        ) = BackupFile(FORMAT, VERSION, exportedAt, recipes, categories, mealLogs)
    }
}

@Serializable
data class BackupRecipe(
    val id: Long,
    val title: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 关联的分类 id。 */
    val categories: List<Long> = emptyList(),
    val ingredients: List<BackupIngredient> = emptyList(),
    val content: List<BackupContentBlock> = emptyList(),
    val images: List<BackupImage> = emptyList(),
)

@Serializable
data class BackupIngredient(
    val name: String,
    val amount: String = "",
    val unit: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class BackupContentBlock(
    /** `DIRECTIONS` 或 `NOTE`。 */
    val section: String,
    /** `LIST_ITEM` 或 `HEADING`。 */
    val kind: String,
    val level: Int = 0,
    val text: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class BackupImage(
    /** zip 内的相对路径，如 `images/ab12.jpg`。 */
    val file: String,
    /** zip 内的相对路径，如 `thumbs/ab12.jpg`；可能是空的。 */
    val thumbFile: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val sortOrder: Int = 0,
    val isCover: Boolean = false,
)

@Serializable
data class BackupCategory(
    val id: Long,
    val name: String,
    val sortOrder: Int = 0,
)

@Serializable
data class BackupMealLog(
    val recipeId: Long,
    val epochDay: Long,
    val slot: String,
    val createdAt: Long = 0,
)

/** 备份文件的编解码。纯 Kotlin，能直接单测。 */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(backup)

    /**
     * 解析备份文件。格式不对就抛 [IllegalArgumentException]，
     * 让界面能给出「这个文件不是本应用的备份」这种提示，而不是一个原始异常。
     */
    fun decode(text: String): BackupFile {
        val backup = try {
            json.decodeFromString<BackupFile>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("这个文件不是食谱本的备份", e)
        }
        require(backup.format == BackupFile.FORMAT) { "这个文件不是食谱本的备份" }
        require(backup.version <= BackupFile.VERSION) { "备份文件来自更新的版本，请先升级应用" }
        return backup
    }
}
