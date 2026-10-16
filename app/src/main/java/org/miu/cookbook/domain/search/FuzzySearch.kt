package org.miu.cookbook.domain.search

/**
 * 内存里的模糊搜索。
 *
 * 为什么不在 SQL 里搜：中文用 `LIKE` 只能做连续子串，而想要的效果是
 * 输「番炒」就能找到「番茄炒蛋」。所以先把每个字的拼音首字母查出来
 * （见 [PinyinTable]），再按**子序列**匹配——查询串里的字按顺序出现在
 * 菜名里就行，不必挨着。
 *
 * 匹配分三档，数字越小越靠前，同一档内保持调用方给的顺序
 * （列表是「最近添加」倒序，所以同分时新的在前）：
 *
 * 1. 菜名：前缀 → 子串 → 逐字子序列 → 拼音首字母子序列（`fqcd`）
 * 2. 分类（`家常菜`）
 * 3. 原料（`鸡蛋`）
 *
 * 纯函数、无 Android 依赖，可以直接用 JVM 单测覆盖。
 */
object FuzzySearch {

    /** 一条记录里参与搜索的字段。 */
    data class Document(
        val title: String,
        val categories: List<String> = emptyList(),
        val ingredients: List<String> = emptyList(),
    )

    private const val SCORE_TITLE_PREFIX = 0
    private const val SCORE_TITLE_SUBSTRING = 100
    private const val SCORE_TITLE_SUBSEQUENCE = 300
    private const val SCORE_TITLE_INITIALS = 500
    private const val SCORE_CATEGORY = 700
    private const val SCORE_INGREDIENT = 900

    /** 命中位置/跳过字符的惩罚上限，保证不会越到下一档去。 */
    private const val MAX_PENALTY = 60

    /** 分类/原料上命中首字母时的一点额外惩罚。 */
    private const val FIELD_INITIALS_PENALTY = 20

    /**
     * 去掉空白与标点、统一小写。汉字本身会被保留，
     * 所以「番茄 炒蛋」「番茄、炒蛋」都能对上「番茄炒蛋」。
     */
    fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(Character.toLowerCase(codePoint))
            }
        }
        return builder.toString()
    }

    /**
     * 文本的拼音首字母串，非汉字原样保留：「番茄炒蛋」→「fqcd」，
     * 「3分钟蛋炒饭」→「3fzdcf」。多音字只取最常见的那个（[initialsOf] 里两个声母
     * 都参与匹配，这里取第一个只是为了方便显示和测试）。
     */
    fun initials(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in normalize(text)) {
            val letters = PinyinTable.initialsOf(ch.code)
            builder.append(if (letters.isEmpty()) ch else letters[0])
        }
        return builder.toString()
    }

    /** 匹配得分；返回 null 表示不匹配。空查询匹配一切。 */
    fun score(document: Document, query: String): Int? {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return 0

        titleScore(normalize(document.title), normalizedQuery)?.let { return it }
        for (category in document.categories) {
            fieldScore(normalize(category), normalizedQuery)?.let { return SCORE_CATEGORY + it }
        }
        for (ingredient in document.ingredients) {
            fieldScore(normalize(ingredient), normalizedQuery)?.let { return SCORE_INGREDIENT + it }
        }
        return null
    }

    /**
     * 过滤 + 重排。查询为空时原样返回（保持「最近添加」的顺序）。
     * [documentOf] 会被逐项调用，调用方最好把 [Document] 预先算好存着。
     */
    fun <T> rank(items: List<T>, query: String, documentOf: (T) -> Document): List<T> {
        if (normalize(query).isEmpty()) return items
        return items
            .mapNotNull { item -> score(documentOf(item), query)?.let { item to it } }
            .sortedBy { (_, score) -> score }
            .map { (item, _) -> item }
    }

    private fun titleScore(title: String, query: String): Int? {
        if (title.isEmpty()) return null
        if (title.startsWith(query)) return SCORE_TITLE_PREFIX
        val at = title.indexOf(query)
        if (at >= 0) return SCORE_TITLE_SUBSTRING + at.coerceAtMost(MAX_PENALTY)
        characterSubsequence(title, query)?.let {
            return SCORE_TITLE_SUBSEQUENCE + it.coerceAtMost(MAX_PENALTY)
        }
        initialSubsequence(title, query)?.let {
            return SCORE_TITLE_INITIALS + it.coerceAtMost(MAX_PENALTY)
        }
        return null
    }

    /**
     * 分类和原料只做子串 + 首字母匹配，不做逐字子序列：
     * 字段短、数量多，逐字子序列太容易擦边命中。
     */
    private fun fieldScore(field: String, query: String): Int? {
        if (field.isEmpty()) return null
        val at = field.indexOf(query)
        if (at >= 0) return at.coerceAtMost(MAX_PENALTY)
        initialSubsequence(field, query)?.let {
            return FIELD_INITIALS_PENALTY + it.coerceAtMost(MAX_PENALTY)
        }
        return null
    }

    /** 查询串是否为文本的子序列；返回跳过的字符数（越紧凑越靠前）。 */
    private fun characterSubsequence(text: String, query: String): Int? {
        var queryIndex = 0
        var penalty = 0
        var lastMatch = -1
        for (index in text.indices) {
            if (queryIndex == query.length) break
            if (text[index] == query[queryIndex]) {
                if (lastMatch >= 0) penalty += index - lastMatch - 1
                lastMatch = index
                queryIndex++
            }
        }
        return if (queryIndex == query.length) penalty else null
    }

    /**
     * 查询串是否为拼音首字母序列的子序列。
     * 每个字可能有两个声母（多音字），命中任意一个即可——「番茄」的「茄」
     * 既是 jiā 也是 qié，所以 fqcd 和 fjcd 都找得到「番茄炒蛋」。
     */
    private fun initialSubsequence(text: String, query: String): Int? {
        if (query.isEmpty()) return 0
        var queryIndex = 0
        var penalty = 0
        var lastMatch = -1
        var index = -1
        for (ch in text) {
            index++
            if (queryIndex == query.length) break
            val letters = PinyinTable.initialsOf(ch.code)
            val hit = if (letters.isEmpty()) ch == query[queryIndex] else letters.contains(query[queryIndex])
            if (hit) {
                if (lastMatch >= 0) penalty += index - lastMatch - 1
                lastMatch = index
                queryIndex++
            }
        }
        return if (queryIndex == query.length) penalty else null
    }
}
