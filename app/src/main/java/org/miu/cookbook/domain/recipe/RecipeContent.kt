package org.miu.cookbook.domain.recipe

/** 原料的一行：「鸡蛋」+「2」+「个」。数量和量词都可以为空。 */
data class IngredientLine(
    val name: String = "",
    val amount: String = "",
    val unit: String = "",
) {
    /** 「鸡蛋 2个」「盐 适量」「番茄」——没写数量就只显示名字。 */
    val display: String
        get() = buildString {
            append(name)
            if (amount.isNotBlank()) {
                append(' ')
                append(amount)
                if (unit.isNotBlank()) append(unit)
            }
        }

    val isBlank: Boolean get() = name.isBlank() && amount.isBlank() && unit.isBlank()
}

/** 做法/备注里的一行：有序列表项（带缩进层级）或三级标题。 */
data class ContentLine(
    val text: String = "",
    /** 缩进层级：0 一级有序列表，1 子列表，2 子子列表。 */
    val level: Int = 0,
    val isHeading: Boolean = false,
)

/** 量词候选。下拉框里可以空着，也可以从这里挑一个；库里其实存的是自由文本。 */
object Units {
    val COMMON: List<String> = listOf(
        "", "g", "kg", "ml", "L",
        "个", "只", "条", "根", "片", "块", "颗", "粒", "张", "段", "朵", "束", "把", "勺", "碗", "杯",
    )

    /**
     * 数量填的是数字、又还没选量词时，默认补一个 g。
     * 这正是需求里说的「如果食材数填写了数字则量词默认填充为 g」。
     */
    fun defaultFor(amount: String, currentUnit: String): String =
        if (currentUnit.isEmpty() && amount.isDigits()) "g" else currentUnit

    private fun String.isDigits(): Boolean = isNotEmpty() && all { it.isDigit() }
}

/**
 * 做法/备注编辑器的全部规则都在这里，界面只负责把按键翻译成对这些函数的调用。
 *
 * 规则（对应需求）：
 * - 行首敲一下空格 → 缩进一级（子有序列表），再敲 → 子子列表，最多两级；
 * - 行首敲退格 → 回到上一级；
 * - 行首敲句号 → 这一行变成三级标题，再敲一下变回列表项；
 * - 换行继承当前层级。
 */
object ContentOutline {

    const val MAX_LEVEL = 2

    fun indent(line: ContentLine): ContentLine =
        if (line.isHeading) line else line.copy(level = (line.level + 1).coerceAtMost(MAX_LEVEL))

    fun outdent(line: ContentLine): ContentLine =
        if (line.isHeading) line else line.copy(level = (line.level - 1).coerceAtLeast(0))

    /** 变成三级标题（或变回来）。标题顶格，所以顺手把行首的空格清掉。 */
    fun toggleHeading(line: ContentLine): ContentLine =
        line.copy(text = line.text.trimStart(), isHeading = !line.isHeading, level = 0)

    /**
     * 每一行显示几号。标题不占编号，而且标题之后编号重新从 1 开始
     * （三级标题本来就是拿来分段的）。
     */
    fun numbers(lines: List<ContentLine>): List<Int> {
        val counters = IntArray(MAX_LEVEL + 1)
        return lines.map { line ->
            if (line.isHeading) {
                for (i in counters.indices) counters[i] = 0
                0
            } else {
                val level = line.level.coerceIn(0, MAX_LEVEL)
                counters[level]++
                for (i in level + 1..MAX_LEVEL) counters[i] = 0
                counters[level]
            }
        }
    }

    /** 存库前收拾一下：掐掉首尾的空行，层级夹到合法范围。 */
    fun normalize(lines: List<ContentLine>): List<ContentLine> {
        // 空行在结构化模型里没有意义（Markdown 解析时也会被丢掉），存库前统一去掉，
        // 免得渲染成「3.」这样只有编号没有内容的行
        return lines
            .map { it.copy(text = it.text.trim(), level = it.level.coerceIn(0, MAX_LEVEL)) }
            .filter { it.text.isNotEmpty() }
    }
}
