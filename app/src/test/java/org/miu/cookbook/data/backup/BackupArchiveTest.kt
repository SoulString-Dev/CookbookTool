package org.miu.cookbook.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.miu.cookbook.domain.backup.BackupFile
import org.miu.cookbook.domain.backup.BackupImage
import org.miu.cookbook.domain.backup.BackupRecipe

/** 备份 zip 的读写：图片进得去、出得来，坏东西进不来。 */
class BackupArchiveTest {

    private lateinit var src: File
    private lateinit var dst: File

    @Before
    fun setUp() {
        src = Files.createTempDirectory("cookbook-src").toFile()
        dst = Files.createTempDirectory("cookbook-dst").toFile()
    }

    @After
    fun tearDown() {
        src.deleteRecursively()
        dst.deleteRecursively()
    }

    private fun writeImage(dir: File, name: String, bytes: ByteArray) {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    @Test
    fun `整份写出再读回来，数据和图片都在`() {
        writeImage(src, "images/a.jpg", byteArrayOf(1, 2, 3, 4))
        writeImage(src, "thumbs/a.jpg", byteArrayOf(9, 9))
        val backup = BackupFile.create(
            recipes = listOf(
                BackupRecipe(
                    id = 7,
                    title = "番茄炒蛋",
                    images = listOf(BackupImage("images/a.jpg", "thumbs/a.jpg", 100, 80, 0, true)),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream()
            .also { BackupArchive.write(backup, src, it) }
            .toByteArray()

        assertEquals(
            listOf("cookbook.json", "images/a.jpg", "thumbs/a.jpg"),
            entryNames(bytes),
        )

        val restored = BackupArchive.read(ByteArrayInputStream(bytes), dst)
        assertEquals(7L, restored.recipes.single().id)
        assertEquals("番茄炒蛋", restored.recipes.single().title)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(dst, "images/a.jpg").readBytes())
        assertArrayEquals(byteArrayOf(9, 9), File(dst, "thumbs/a.jpg").readBytes())
    }

    @Test
    fun `图片文件不在磁盘上时跳过它，备份照样能写出来`() {
        val backup = BackupFile.create(
            recipes = listOf(
                BackupRecipe(
                    id = 1,
                    title = "只有记录的菜",
                    images = listOf(BackupImage("images/gone.jpg", "thumbs/gone.jpg")),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream()
            .also { BackupArchive.write(backup, src, it) }
            .toByteArray()

        assertEquals(listOf("cookbook.json"), entryNames(bytes))
        assertEquals(1, BackupArchive.read(ByteArrayInputStream(bytes), dst).recipes.size)
    }

    @Test
    fun `zip 里没有数据文件时报错`() {
        val bytes = ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("readme.txt"))
                    zip.write("hi".toByteArray())
                    zip.closeEntry()
                }
            }
            .toByteArray()

        try {
            BackupArchive.read(ByteArrayInputStream(bytes), dst)
            fail("应该抛异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("备份文件里没有数据", e.message)
        }
    }

    @Test
    fun `条目名想往外跳时会被挡住`() {
        val bytes = ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(BackupArchive.ENTRY_JSON))
                    zip.write("""{"format":"cookbook-backup","version":1}""".toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("images/../../../evil.jpg"))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }
            .toByteArray()

        BackupArchive.read(ByteArrayInputStream(bytes), dst)

        assertFalse(File(dst.parentFile, "evil.jpg").exists())
        assertFalse(File(dst, "evil.jpg").exists())
        assertTrue(dst.listFiles().orEmpty().none { it.name == "evil.jpg" })
    }
}
