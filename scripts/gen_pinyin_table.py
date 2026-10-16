#!/usr/bin/env python3
"""从 Unicode Unihan 数据库生成汉字拼音首字母表（Kotlin 源码）。

为什么要生成而不是引依赖：搜索结果要支持「fqcd」→「番茄炒蛋」，
但 pinyin4j 之类的库要带几百 KB 的资源文件，而这里只需要
「每个常用汉字可能是哪几个声母」。

输入：Unihan.zip（https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip）
输出：app/src/main/java/org/miu/cookbook/domain/search/PinyinTable.kt

表结构：U+4E00..U+9FFF 共 20992 个码位，每个码位 2 个字节：
第一个是该字最常见的声母，第二个是第二常见声母（0 表示没有）。
取两个是因为多音字很常见，而食物名里恰好踩得到——「番茄」的「茄」
在《现代汉语词典》里 jiā 排在 qié 前面，只取一个就搜不出 fqcd。

整张表 zlib 压缩后 Base64 写入源码（约 15 KB），运行时解压一次。
"""
import base64
import sys
import unicodedata
import zipfile

START = 0x4E00
END = 0x9FFF
SIZE = END - START + 1

OUT = "app/src/main/java/org/miu/cookbook/domain/search/PinyinTable.kt"

# 读音字段的优先级：先说“这个字最常用怎么念”，再说“还有别的念法”。
FIELDS = (
    "kMandarin",
    "kXHC1983",
    "kHanyuPinyin",
    "kHanyuPinlu",
    "kTGHZ2013",
    "kSMSZD2003Readings",
)

HEADER = '''package org.miu.cookbook.domain.search

/**
 * 汉字 → 拼音声母表，由 `scripts/gen_pinyin_table.py` 从 Unicode Unihan 生成，
 * 覆盖 U+4E00–U+9FFF 的 20992 个码位，每个码位两个字节：
 *
 * - 第 1 字节：最常见的声母，1..26 依次代表 a..z，0 表示无读音
 * - 第 2 字节：第二常见的声母，0 表示没有
 *
 * 之所以要第二声母：多音字很常见，而食物名里恰好踩得到——「番茄」的「茄」
 * 在《现代汉语词典》里 jiā 排在 qié 前面，只留一个声母就搜不出 fqcd。
 *
 * 二进制表 zlib 压缩后 Base64 存进源码，运行时解压一次（见 [table]）。
 */
internal object PinyinTable {

    const val START = 0x%X

    const val SIZE = %d

    private const val DATA =
'''

TAIL = '''

    private val table: ByteArray by lazy {
        val compressed = java.util.Base64.getDecoder().decode(DATA)
        val out = ByteArray(SIZE * 2)
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(compressed)
            var offset = 0
            while (offset < out.size) {
                val read = inflater.inflate(out, offset, out.size - offset)
                if (read == 0) break
                offset += read
            }
        } finally {
            inflater.end()
        }
        out
    }

    /**
     * 码位的所有声母（小写，最多两个，按常见程度排序）；不是汉字或表里
     * 没有读音时返回空串。
     */
    fun initialsOf(codePoint: Int): String {
        val index = codePoint - START
        if (index < 0 || index >= SIZE) return ""
        val first = table[index * 2].toInt()
        val second = table[index * 2 + 1].toInt()
        return buildString(2) {
            if (first != 0) append('a' + first - 1)
            if (second != 0) append('a' + second - 1)
        }
    }
}
'''


def initial_of(syllable: str):
    """'qié' / 'chóng' → 'q' / 'c'；取不到字母时返回 None。"""
    for ch in unicodedata.normalize("NFD", syllable.lower()):
        if unicodedata.combining(ch):
            continue
        if "a" <= ch <= "z":
            return ch
        return None
    return None


def readings_of(field: str, value: str):
    """kXHC1983 的值形如 '0541.070:jiā 0923.060:qié'，kMandarin 则是纯拼音。"""
    out = []
    for token in value.split():
        if ":" in token:
            token = token.split(":", 1)[1]
        for syllable in token.split(","):
            letter = initial_of(syllable)
            if letter:
                out.append(letter)
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else ".toolchain/tmp/Unihan.zip"
    table = bytearray(SIZE * 2)
    seen = 0
    with zipfile.ZipFile(src) as z:
        per_codepoint = {}
        with z.open("Unihan_Readings.txt") as f:
            for raw in f:
                line = raw.decode("utf-8", "replace")
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 3 or parts[1] not in FIELDS:
                    continue
                code = parts[0]
                if not code.startswith("U+"):
                    continue
                try:
                    cp = int(code[2:], 16)
                except ValueError:
                    continue
                if not (START <= cp <= END):
                    continue
                per_codepoint.setdefault(cp, {})[parts[1]] = parts[2]

        for cp, fields in per_codepoint.items():
            letters = []
            for name in FIELDS:
                if name not in fields:
                    continue
                for letter in readings_of(name, fields[name]):
                    if letter not in letters:
                        letters.append(letter)
                    if len(letters) == 2:
                        break
                if len(letters) == 2:
                    break
            if not letters:
                continue
            seen += 1
            base = (cp - START) * 2
            table[base] = ord(letters[0]) - ord("a") + 1
            if len(letters) > 1:
                table[base + 1] = ord(letters[1]) - ord("a") + 1

    import zlib

    compressed = zlib.compress(bytes(table), 9)
    b64 = base64.b64encode(compressed).decode("ascii")
    lines = [b64[i : i + 96] for i in range(0, len(b64), 96)]
    body = "\n".join(
        '        "%s"%s' % (ln, " +" if i < len(lines) - 1 else "")
        for i, ln in enumerate(lines)
    )

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(HEADER % (START, SIZE))
        f.write(body)
        f.write(TAIL)
    print("已生成 %s：%d 个码位有读音，压缩后 %d 字节" % (OUT, seen, len(compressed)))


if __name__ == "__main__":
    main()
