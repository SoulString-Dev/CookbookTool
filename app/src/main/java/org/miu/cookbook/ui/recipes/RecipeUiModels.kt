package org.miu.cookbook.ui.recipes

import org.miu.cookbook.data.db.BlockKind
import org.miu.cookbook.data.db.ContentBlockEntity
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.IngredientEntity
import org.miu.cookbook.data.db.RecipeDetail
import org.miu.cookbook.data.db.RecipeListRow
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.IngredientLine
import org.miu.cookbook.domain.search.FuzzySearch
import java.io.File

/** 列表页的一行。 */
data class RecipeListItem(
    val id: Long,
    val title: String,
    val categories: List<String>,
    val ingredients: List<IngredientLine>,
    /** 列表封面的绝对路径；没有图片时为 null，界面留占位。 */
    val coverPath: String?,
    /** 预先算好，搜索时不必每敲一个字重新拼。 */
    val searchDocument: FuzzySearch.Document,
) {
    /** 右下角那行原料摘要，太长交给界面省略。 */
    val ingredientSummary: String
        get() = if (ingredients.isEmpty()) {
            ""
        } else {
            ingredients.take(MAX_SUMMARY_INGREDIENTS).joinToString(" · ") { it.display }
        }

    private companion object {
        const val MAX_SUMMARY_INGREDIENTS = 4
    }
}

/** 详情页渲染用。 */
data class RecipeDetailUi(
    val id: Long,
    val title: String,
    val categories: List<String>,
    val ingredients: List<IngredientLine>,
    val directions: List<ContentLine>,
    val notes: List<ContentLine>,
    /** 最多六张图，已按顺序排好。 */
    val imagePaths: List<String>,
)

/** 一道菜最多留六张图（需求定的）。 */
const val MAX_IMAGES = 6

fun RecipeListRow.toListItem(baseDir: File): RecipeListItem {
    val ingredientLines = ingredients.sortedBy { it.sortOrder }.map { it.toLine() }
    val categoryNames = categories.sortedBy { it.sortOrder }.map { it.name }
    return RecipeListItem(
        id = recipe.id,
        title = recipe.title,
        categories = categoryNames,
        ingredients = ingredientLines,
        coverPath = images.coverPath(baseDir),
        searchDocument = FuzzySearch.Document(
            title = recipe.title,
            categories = categoryNames,
            ingredients = ingredientLines.map { it.name },
        ),
    )
}

fun RecipeDetail.toDetailUi(baseDir: File): RecipeDetailUi {
    val ingredientLines = ingredients.sortedBy { it.sortOrder }.map { it.toLine() }
    return RecipeDetailUi(
        id = recipe.id,
        title = recipe.title,
        categories = categories.sortedBy { it.sortOrder }.map { it.name },
        ingredients = ingredientLines,
        directions = contentBlocks.linesOf(ContentSection.DIRECTIONS),
        notes = contentBlocks.linesOf(ContentSection.NOTE),
        imagePaths = images.sortedBy { it.sortOrder }.take(MAX_IMAGES).map { File(baseDir, it.path).absolutePath },
    )
}

private fun IngredientEntity.toLine() = IngredientLine(name = name, amount = amount, unit = unit)

/** 编辑页要用：把库里存的正文行取出来（详情页走 [toDetailUi] 已经分好了）。 */
fun RecipeDetail.contentLines(section: ContentSection): List<ContentLine> =
    contentBlocks.linesOf(section)

private fun List<ContentBlockEntity>.linesOf(section: ContentSection): List<ContentLine> =
    filter { it.section == section }
        .sortedBy { it.sortOrder }
        .map { ContentLine(text = it.text, level = it.level, isHeading = it.kind == BlockKind.HEADING) }

/**
 * 封面：显式设了 `is_cover` 就用它，否则用第一张。
 * 库里存的是相对 filesDir 的路径，界面上要用绝对路径。
 */
private fun List<org.miu.cookbook.data.db.RecipeImageEntity>.coverPath(baseDir: File): String? {
    if (isEmpty()) return null
    val ordered = sortedBy { it.sortOrder }
    val cover = ordered.firstOrNull { it.isCover } ?: ordered.first()
    return File(baseDir, cover.thumbPath.ifBlank { cover.path }).absolutePath
}
