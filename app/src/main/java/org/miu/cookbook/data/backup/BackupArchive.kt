package org.miu.cookbook.data.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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

    fun write(backup: BackupFile, baseDir: File, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_JSON))
            zip.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            backup.recipes.forEach { recipe ->
                recipe.images.forEach { image ->
                    copyInto(zip, File(baseDir, image.file), image.file)
                    if (image.thumbFile.isNotBlank()) {
                        copyInto(zip, File(baseDir, image.thumbFile), image.thumbFile)
                    }
                }
            }
        }
    }

    /** 图片文件不在了就跳过：备份里少一张图，总比整个备份失败好。 */
    private fun copyInto(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * 读出备份，并把图片解到 [baseDir] 下（调用方负责先把旧的 `images/`、`thumbs/` 清掉）。
     */
    fun read(input: InputStream, baseDir: File): BackupFile {
        var backup: BackupFile? = null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                when {
                    entry.name == ENTRY_JSON ->
                        backup = BackupCodec.decode(zip.readBytes().toString(Charsets.UTF_8))

                    entry.name.startsWith(DIR_IMAGES) || entry.name.startsWith(DIR_THUMBS) -> {
                        val target = File(baseDir, entry.name)
                        // 防目录穿越：zip 里的条目名是不可信输入
                        if (target.canonicalPath.startsWith(baseDir.canonicalPath + File.separator)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return backup ?: throw IllegalArgumentException("备份文件里没有数据")
    }
}
