package org.miu.cookbook.data.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.miu.cookbook.domain.backup.BackupCodec
import org.miu.cookbook.domain.backup.BackupFile

/**
 * 备份文件就是个 zip：
 *
 * ```
 * cookbook.json          数据（可读的 JSON，图片按文件名引用）
 * images/ab12.jpg        原图（长边 2048 那份）
 * thumbs/ab12.jpg        缩略图
 * ```
 *
 * 用 zip 而不是把图片塞进 JSON 的 base64，是因为图片往往占备份体积的九成以上，
 * base64 还要再涨三分之一，而且解压时得把整个 JSON 读进内存。
 */
object BackupArchive {

    const val ENTRY_JSON = "cookbook.json"
    private const val DIR_IMAGES = "images/"
    private const val DIR_THUMBS = "thumbs/"

    /** 备份里的 JSON 最多能有多大（解压后）。真实规模远小于此，只是不让坏文件把内存吃干。 */
    private const val MAX_JSON_BYTES = 32L * 1024 * 1024

    /** 单个图片条目的上限。图片经 [org.miu.cookbook.data.image.ImageStore] 压过，远超这个数就不正常。 */
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 解压出来的图片总量上限，防「高压缩比 zip 炸弹」把磁盘写满。 */
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    fun write(backup: BackupFile, baseDir: File, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_JSON))
            zip.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 两道菜共用一张图时会撞条目名，zip 里重复条目会让解压端报错，所以按名字去重。
            val written = HashSet<String>()
            backup.recipes.forEach { recipe ->
                recipe.images.forEach { image ->
                    copyInto(zip, File(baseDir, image.file), image.file, written)
                    if (image.thumbFile.isNotBlank()) {
                        copyInto(zip, File(baseDir, image.thumbFile), image.thumbFile, written)
                    }
                }
            }
        }
    }

    /** 图片文件不在了就跳过：备份里少一张图，总比整个备份失败好。 */
    private fun copyInto(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
        written: MutableSet<String>,
    ) {
        if (!file.isFile) return
        if (!written.add(entryName)) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * 读出备份，并把图片解到 [baseDir] 下（调用方负责先把旧的 `images/`、`thumbs/` 清掉）。
     *
     * zip 里的一切都当不可信输入：条目名可能重复、单项可能很大、总量可能是个 zip 炸弹，
     * 所以体积有上限、重名只认第一条、解压总量也封顶。
     */
    fun read(input: InputStream, baseDir: File): BackupFile {
        var backup: BackupFile? = null
        var total = 0L
        val seen = HashSet<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !seen.add(entry.name)) {
                    zip.closeEntry()
                    continue
                }
                when {
                    entry.name == ENTRY_JSON -> {
                        val json = ByteArrayOutputStream()
                        copyAtMost(zip, json, MAX_JSON_BYTES)
                        backup = BackupCodec.decode(json.toString(Charsets.UTF_8.name()))
                    }

                    entry.name.startsWith(DIR_IMAGES) || entry.name.startsWith(DIR_THUMBS) -> {
                        val target = File(baseDir, entry.name)
                        // 防目录穿越：zip 里的条目名是不可信输入
                        if (target.canonicalPath.startsWith(baseDir.canonicalPath + File.separator)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                total += copyAtMost(
                                    zip,
                                    out,
                                    minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total),
                                )
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return backup ?: throw IllegalArgumentException("备份文件里没有数据")
    }

    /** 最多拷 [limit] 字节，超了就判这个文件不是我们导出的备份。 */
    private fun copyAtMost(input: InputStream, out: OutputStream, limit: Long): Long {
        if (limit <= 0) throw IllegalArgumentException("备份文件太大了，解压会占满手机存储")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > limit) throw IllegalArgumentException("备份文件太大了，解压会占满手机存储")
            out.write(buffer, 0, read)
        }
        return copied
    }
}
