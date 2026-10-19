package org.miu.cookbook.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** 导入成功后落盘的结果，路径都是相对 [File] `filesDir` 的。 */
data class ImportedImage(
    val path: String,
    val thumbPath: String,
    val width: Int,
    val height: Int,
    /**
     * 内容哈希（v3 起算）。同步**只认哈希不认路径**：路径换台设备就失效了。
     * 算不出来时是空串——图照样能看能导，只是不会被上传（宁可少一张图，也不要传一个对不上的引用）。
     */
    val sha256: String = "",
    val thumbSha256: String = "",
)

/**
 * 图片入库前的处理：**一律复制进应用私有目录并压到长边 2048**。
 *
 * 不保留原图有两个原因：SAF 给的授权会过期、相册里的原图也可能被删，
 * 一旦那样，库里的路径就变成死链；而缩略图单独存一份，列表滚动时不用解码大图。
 */
object ImageStore {

    /** 长边上限。 */
    const val MAX_EDGE = 2048

    /** 缩略图长边。 */
    const val THUMB_EDGE = 320

    const val IMAGE_DIR = "images"
    const val THUMB_DIR = "thumbs"

    /** 采样率：让解码出来的位图最长边不超过 [maxEdge]（2 的幂，BitmapFactory 的规矩）。纯函数，好测。 */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val longest = maxOf(width, height)
        var sample = 1
        // 宁可小一点也不要想少：一张 12MP 的照片按原尺寸解码就是 48MB
        while (longest / sample > maxEdge) sample *= 2
        return sample
    }

    /** 缩放后的目标尺寸：只缩不放，长边等于 [maxEdge]。 */
    fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= 0 || longest <= maxEdge) return width to height
        val scale = maxEdge.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    suspend fun importFromUri(context: Context, uri: Uri, baseDir: File): ImportedImage? {
        val resolver = context.contentResolver
        return save({ resolver.openInputStream(uri) }, baseDir)
    }

    suspend fun importFromFile(file: File, baseDir: File): ImportedImage? =
        save({ file.inputStream() }, baseDir)

    /** 删掉一张图片的两个文件（库里那一行由仓库负责删）。 */
    fun deleteFiles(baseDir: File, path: String, thumbPath: String) {
        if (path.isNotBlank()) runCatching { File(baseDir, path).delete() }
        if (thumbPath.isNotBlank() && thumbPath != path) {
            runCatching { File(baseDir, thumbPath).delete() }
        }
    }

    /**
     * 算一个文件的 sha256（流式读，别把几十兆的图整个塞进内存）。
     *
     * 同步靠它做内容寻址：同一张图被两道菜引用、或者两台设备各拍了一张一样的图，
     * 服务端都只存一份。读不出来（文件被删/没权限）返回 null，调用方当「这张图传不了」处理。
     */
    fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 只读尺寸。**注意 `inJustDecodeBounds = true` 时 `decodeStream` 的返回值就是 null**，
     * 别拿它判断成败——要看 `outWidth/outHeight` 有没有被填上。
     * （这里踩过坑：写成 `?: return null` 会让每一次导入都失败。）
     */
    private fun readBounds(openStream: () -> InputStream?): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = openStream() ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        return if (options.outWidth > 0 && options.outHeight > 0) options else null
    }

    // 统一走流：拍照得到的是临时文件，相册给的是 content Uri，两边都用同一条解码路径。
    // 文件损坏、权限收回、内存不够都退回「读不出来」，别把界面炸掉：位图在 finally 里放掉，
    // 写了一半的文件在 catch 里删掉。
    private fun save(openStream: () -> InputStream?, baseDir: File): ImportedImage? {
        val bitmaps = mutableListOf<Bitmap>()
        val written = mutableListOf<File>()

        // createScaledBitmap 在尺寸没变时会返回同一个对象，所以按身份去重，保证每张只 recycle 一次
        fun track(bitmap: Bitmap): Bitmap {
            if (bitmaps.none { it === bitmap }) bitmaps += bitmap
            return bitmap
        }

        return try {
            val bounds = readBounds(openStream) ?: return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            }
            val decodeStream = openStream() ?: return null
            val decoded = track(decodeStream.use { BitmapFactory.decodeStream(it, null, options) } ?: return null)

            val orientation = runCatching {
                openStream()?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull()
            val fixed = track(applyOrientation(decoded, orientation))

            val (targetWidth, targetHeight) = targetSize(fixed.width, fixed.height, MAX_EDGE)
            val scaled = track(
                if (targetWidth != fixed.width || targetHeight != fixed.height) {
                    Bitmap.createScaledBitmap(fixed, targetWidth, targetHeight, true)
                } else {
                    fixed
                },
            )

            val name = "${UUID.randomUUID()}.jpg"
            val imageDir = File(baseDir, IMAGE_DIR).apply { mkdirs() }
            val thumbDir = File(baseDir, THUMB_DIR).apply { mkdirs() }

            val imageFile = File(imageDir, name)
            FileOutputStream(imageFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            written += imageFile

            val (thumbWidth, thumbHeight) = targetSize(scaled.width, scaled.height, THUMB_EDGE)
            val thumb = track(
                if (thumbWidth != scaled.width || thumbHeight != scaled.height) {
                    Bitmap.createScaledBitmap(scaled, thumbWidth, thumbHeight, true)
                } else {
                    scaled
                },
            )
            val thumbFile = File(thumbDir, name)
            FileOutputStream(thumbFile).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            written += thumbFile

            ImportedImage(
                path = "$IMAGE_DIR/$name",
                thumbPath = "$THUMB_DIR/$name",
                width = targetWidth,
                height = targetHeight,
                sha256 = sha256Of(imageFile).orEmpty(),
                thumbSha256 = sha256Of(thumbFile).orEmpty(),
            )
        } catch (t: Throwable) {
            // 正图写出来了、缩略图没写出来这种半成品要收干净，别留孤儿文件
            written.forEach { it.delete() }
            null
        } finally {
            // 连续导入几张就是几十 MB 的位图，用完就放掉
            bitmaps.forEach { it.recycle() }
        }
    }

    /** 手机横着拍的照片方向信息在 EXIF 里，按它把像素转正，不然进详情页是歪的。 */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int?): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
