package org.miu.cookbook.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音表的完整性检查。
 *
 * 这张表是脚本从 Unihan 生成的二进制（zlib + Base64），人眼没法审。它一旦坏了，
 * 表现是「搜 fqcd 搜不到番茄炒蛋」——功能没报错，只是悄悄不灵。所以这里守住三件事：
 * 表能解压、长度对得上、常用字与多音字的声母没串位。
 */
class PinyinTableTest {

    @Test
    fun `表能解压，长度覆盖整个常用汉字区`() {
        // 首尾都取得到：越界会返回空串，长度不对则会在这里露馅
        assertTrue("第一个码位应该有读音", PinyinTable.initialsOf(PinyinTable.START).isNotEmpty())
        assertTrue(
            "最后一个码位应该在范围内（越界返回空串，但这里要确认表长度够）",
            PinyinTable.initialsOf(PinyinTable.START + PinyinTable.SIZE - 1).isNotEmpty(),
        )
        assertEquals("超出汉字区，应该返回空", "", PinyinTable.initialsOf(PinyinTable.START + PinyinTable.SIZE))
        assertEquals("ASCII 不是汉字", "", PinyinTable.initialsOf('a'.code))
    }

    @Test
    fun `常用食材名的声母都对`() {
        val cases = mapOf(
            '番' to 'f', '蛋' to 'd', '肉' to 'r', '鱼' to 'y',
            '汤' to 't', '葱' to 'c', '姜' to 'j', '蒜' to 's', '辣' to 'l',
        )

        cases.forEach { (char, initial) ->
            val initials = PinyinTable.initialsOf(char.code)
            assertTrue(
                "「$char」的声母应该是 $initial，实际是「$initials」",
                initials.firstOrNull() == initial,
            )
        }
    }

    @Test
    fun `多音字带上第二个声母`() {
        // 「茄」在《现代汉语词典》里 jiā 排在 qié 前面：只留一个声母就搜不出 fqcd
        val initials = PinyinTable.initialsOf('茄'.code)
        assertEquals("「茄」应该是「jq」（词典里 jiā 排在 qié 前面）", "jq", initials)
        assertTrue("「茄」的声母里要有 q", initials.contains('q'))
        assertTrue("「茄」的声母里要有 j", initials.contains('j'))
    }

    @Test
    fun `整张表里没有小写字母之外的东西`() {
        // 表里用 1..26 编码 a..z，0 表示没有；越界编码说明生成脚本写坏了
        val bad = mutableListOf<Int>()
        for (code in PinyinTable.START until PinyinTable.START + PinyinTable.SIZE) {
            val initials = PinyinTable.initialsOf(code)
            if (initials.any { it !in 'a'..'z' } || initials.length > 2) bad += code
        }
        assertTrue("这些码位的声母不合法：${bad.take(8)}", bad.isEmpty())
    }
}
