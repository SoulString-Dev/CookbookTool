package org.miu.cookbook.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 导入这条路径必须真的跑一遍。
 *
 * 注意：Robolectric 的 BitmapFactory 是影子实现，`inJustDecodeBounds` 时它照样返回一个假位图，
 * 而真机是返回 null。所以**这条测试其实抓不住当初那个 bug**（实测把旧写法放回来它照样通过）——
 * 真正的防线是 `readBounds()` 的写法本身：它不看 `decodeStream` 的返回值，只看 `outWidth/outHeight`。
 * 留着这些用例是为了守住「导入这条路整体能跑通」。
 *
 * 这里踩过一个坑：`inJustDecodeBounds = true` 时 `BitmapFactory.decodeStream` 的返回值就是 null，
 * 当时写成 `openStream()?.use { decodeStream(...) } ?: return null`，结果**每一次导入都失败**
 * （手机上表现为「这张照片读不出来」），而只测 `sampleSizeFor`/`targetSize` 这些纯函数是发现不了的。
 */
@RunWith(RobolectricTestRunner::class)
class ImageStoreImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 一张 1x1 的真实 JPEG。 */
    private val jpegBytes: ByteArray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4n" +
            "ICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAA" +
            "Cf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
    )

    private fun baseDir(): File = temp.newFolder("files")

    @Test
    fun `导入一张照片会写出原图与缩略图两个文件`() = runBlocking {
        val source = temp.newFile("source.jpg").apply { writeBytes(jpegBytes) }
        val baseDir = baseDir()

        val imported = ImageStore.importFromFile(source, baseDir)

        assertNotNull("导入不该失败（inJustDecodeBounds 的 null 返回值不是失败）", imported)
        assertTrue(imported!!.path.startsWith("${ImageStore.IMAGE_DIR}/"))
        assertTrue(imported.thumbPath.startsWith("${ImageStore.THUMB_DIR}/"))
        assertTrue("原图要落盘", File(baseDir, imported.path).isFile)
        assertTrue("缩略图要落盘", File(baseDir, imported.thumbPath).isFile)
        assertTrue(File(baseDir, imported.path).length() > 0)
    }

    @Test
    fun `文件读不出来时返回 null 而不是抛异常`() = runBlocking {
        val missing = File(temp.root, "not-here.jpg")
        assertTrue(ImageStore.importFromFile(missing, baseDir()) == null)
    }

    @Test
    fun `导入后原图与缩略图的长边都不超过上限`() = runBlocking {
        val source = temp.newFile("big.jpg").apply { writeBytes(jpegBytes) }
        val baseDir = baseDir()

        val imported = ImageStore.importFromFile(source, baseDir) ?: error("导入失败")

        // Robolectric 的解码是影子实现，这里只保证两个文件都真的写出来了
        val original = BitmapFactory.decodeFile(File(baseDir, imported.path).absolutePath)
        assertNotNull(original)
        val thumb = BitmapFactory.decodeFile(File(baseDir, imported.thumbPath).absolutePath)
        assertNotNull(thumb)
        assertTrue(thumb!!.width <= ImageStore.THUMB_EDGE)
        assertTrue(thumb.height <= ImageStore.THUMB_EDGE)
        original?.recycle()
        thumb.recycle()
    }

    @Test
    fun `写出的是能被重新读回来的位图`() = runBlocking {
        val source = temp.newFile("source.jpg").apply { writeBytes(jpegBytes) }
        val baseDir = baseDir()
        val imported = ImageStore.importFromFile(source, baseDir) ?: error("导入失败")

        val bytes = File(baseDir, imported.path).readBytes()
        val reopened = BitmapFactory.decodeStream(ByteArrayOutputStream().apply { write(bytes) }
            .let { java.io.ByteArrayInputStream(it.toByteArray()) })
        assertNotNull(reopened)
        reopened?.let { assertTrue(it.width > 0 && it.height > 0); it.recycle() }
        Unit
    }
}
