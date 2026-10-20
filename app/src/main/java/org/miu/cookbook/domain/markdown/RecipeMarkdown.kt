package org.miu.cookbook.domain.markdown

import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.ContentOutline
import org.miu.cookbook.domain.recipe.IngredientLine

/** 一份食谱的纯数据表示，导入导出都走它。 */
data class MarkdownRecipe(
    val title: String,
    val categories: List<String> = emptyList(),
    val ingredients: List<IngredientLine> = emptyList(),
    val directions: List<ContentLine> = emptyList(),
    val notes: List<ContentLine> = emptyList(),
)

/**
 * 食谱的 Markdown 序列化 / 反序列化。
 *
 * 注意：**Markdown 不是数据库格式**。库里的真相是结构化的行，这里只是把它
 * 渲染成一份人看得懂、也丢得回来的文本（导出备份、导入恢复、以及详情页
 * 里「看看 Markdown 长什么样」）。
 *
 * 格式：
 * ```
 * # 番茄炒蛋
 *
 * 标签：家常菜 快手菜
 *
 * ## 原料
 *
 * 1. 鸡蛋 2 个
 *
 * ## 做法
 *
 * 1. 鸡蛋打散
 *   1. 加一点盐
 * ### 火候
 * 1. 中火
 * ```
 */
object RecipeMarkdown {

    const val TAG_PREFIX = "标签："
    private const val SECTION_INGREDIENTS = "## 原料"
    private const val SECTION_DIRECTIONS = "## 做法"
    private const val SECTION_NOTES = "## 备注"

    private val INGREDIENT_HEADERS = setOf("原料", "食材", "用料", "材料")
    private val DIRECTIONS_HEADERS = setOf("做法", "步骤", "制作", "作法")
    private val NOTES_HEADERS = setOf("备注", "小贴士", "提示", "注意")

    /**
     * 列表项：可选的缩进 + 序号（1. / 1、/ 1)）+ 内容。
     *
     * 句点后面**紧跟数字**的不算序号：否则「1.5 升水」会被当成第 1 项「5 升水」，
     * 一份写着用量的食谱导进来就乱了（顿号与右括号没有这个歧义）。
     */
    private val LIST_ITEM = Regex("""^(\s*)\d+\s*(?:[、)]|\.(?!\d))\s*(.*)$""")

    // ---------- 写 ----------

    fun serialize(recipe: MarkdownRecipe): String {
        val sb = StringBuilder()
        sb.append("# ").append(recipe.title.trim()).append('\n')
        if (recipe.categories.isNotEmpty()) {
            sb.append('\n').append(TAG_PREFIX)
                .append(recipe.categories.joinToString(" ") { it.trim() }).append('\n')
        }
        if (recipe.ingredients.isNotEmpty()) {
            sb.append('\n').append(SECTION_INGREDIENTS).append("\n\n")
            recipe.ingredients.forEach { sb.append("1. ").append(it.serialize()).append('\n') }
        }
        appendLines(sb, SECTION_DIRECTIONS, recipe.directions)
        appendLines(sb, SECTION_NOTES, recipe.notes)
        return sb.toString()
    }

    private fun appendLines(sb: StringBuilder, header: String, lines: List<ContentLine>) {
        if (lines.isEmpty()) return
        sb.append('\n').append(header).append("\n\n")
        val numbers = ContentOutline.numbers(lines)
        var pendingHeading = false
        lines.forEachIndexed { index, line ->
            if (line.isHeading) {
                // 标题段和列表之间空一行，读起来才不挤
                if (index > 0) sb.append('\n')
                sb.append("### ").append(line.text).append('\n')
                pendingHeading = true
            } else {
                if (pendingHeading) {
                    sb.append('\n')
                    pendingHeading = false
                }
                sb.append("  ".repeat(line.level))
                    .append(numbers[index]).append(". ").append(line.text).append('\n')
            }
        }
    }

    /** 「鸡蛋 2 个」/「盐 适量」/「番茄」。 */
    private fun IngredientLine.serialize(): String = buildString {
        append(name.trim())
        if (amount.isNotBlank()) {
            append(' ').append(amount.trim())
            if (unit.isNotBlank()) append(' ').append(unit.trim())
        }
    }

    // ---------- 读 ----------

    fun parse(markdown: String): MarkdownRecipe {
        var title = ""
        val categories = mutableListOf<String>()
        val ingredients = mutableListOf<IngredientLine>()
        val directions = mutableListOf<ContentLine>()
        val notes = mutableListOf<ContentLine>()
        var section = ""

        markdown.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit

                line.startsWith("### ") -> {
                    val text = line.removePrefix("### ").trim()
                    if (text.isNotEmpty()) {
                        when (section) {
                            SECTION_DIRECTIONS -> directions += ContentLine(text, isHeading = true)
                            SECTION_NOTES -> notes += ContentLine(text, isHeading = true)
                        }
                    }
                }

                line.startsWith("## ") -> {
                    val name = line.removePrefix("## ").trim()
                    section = when {
                        name in INGREDIENT_HEADERS -> SECTION_INGREDIENTS
                        name in DIRECTIONS_HEADERS -> SECTION_DIRECTIONS
                        name in NOTES_HEADERS -> SECTION_NOTES
                        else -> ""
                    }
                }

                line.startsWith("# ") -> if (title.isEmpty()) title = line.removePrefix("# ").trim()

                else -> {
                    val tags = parseTagLine(line)
                    if (tags != null) {
                        categories += tags
                        return@forEach
                    }
                    val item = LIST_ITEM.matchEntire(line)
                    val (level, text) = if (item != null) {
                        val indent = item.groupValues[1].replace("\t", "  ").length
                        (indent / 2).coerceIn(0, ContentOutline.MAX_LEVEL) to item.groupValues[2].trim()
                    } else {
                        // 章节里没编号的裸行（比如「1.5 升就够」这种手写的补充说明）
                        // 也收进来当一行正文：宁可多一行，也别在导入时把它悄悄丢掉
                        0 to line.trim()
                    }
                    if (text.isEmpty()) return@forEach
                    when (section) {
                        SECTION_INGREDIENTS -> ingredients += parseIngredient(text)
                        SECTION_DIRECTIONS -> directions += ContentLine(text, level = level)
                        SECTION_NOTES -> notes += ContentLine(text, level = level)
                    }
                }
            }
        }

        return MarkdownRecipe(
            title = title,
            categories = categories.distinct(),
            ingredients = ingredients,
            directions = directions,
            notes = notes,
        )
    }

    private fun parseTagLine(line: String): List<String>? {
        val body = when {
            line.startsWith(TAG_PREFIX) -> line.removePrefix(TAG_PREFIX)
            line.startsWith("标签:") -> line.removePrefix("标签:")
            line.startsWith("tags:", ignoreCase = true) -> line.substring(5)
            else -> return null
        }
        return body.split(' ', ',', '，', '、', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 反着拼原料。生成时是「名字 [数量 [量词]]」，
     * 所以：第一个词是名字，剩下的如果只有一个词就当成数量（“适量”），
     * 有两个及以上时第一个是数量、其余拼回量词。
     */
    private fun parseIngredient(text: String): IngredientLine {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return IngredientLine()
        if (tokens.size == 1) return IngredientLine(name = tokens[0])
        val name = tokens[0]
        val rest = tokens.drop(1)
        return if (rest.size == 1) {
            IngredientLine(name = name, amount = rest[0])
        } else if (isAmount(rest[0])) {
            IngredientLine(name = name, amount = rest[0], unit = rest.drop(1).joinToString(""))
        } else {
            IngredientLine(name = name, amount = rest.joinToString(" "))
        }
    }

    /**
     * 「1」「2」「1.5」「0.5」都算量，其中小数点只认一个。
     *
     * 以前只认纯整数，`1.5 勺` 会被整个当成量、`unit` 留空；界面上把 1 改成 2 再改回 1.5，
     * 量词就永久丢了（往返一次字段漂移一次）。
     */
    private fun isAmount(token: String): Boolean {
        if (token.isEmpty() || token.none { it.isDigit() }) return false
        var seenDot = false
        for (ch in token) {
            when {
                ch.isDigit() -> Unit
                ch == '.' && !seenDot -> seenDot = true
                else -> return false
            }
        }
        return true
    }
}
