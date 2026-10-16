package org.miu.cookbook.data.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只测纯计算的这两步：真机上的解码/压缩要靠设备验收（Robolectric 的 Bitmap 是影子实现，
 * 测不出真实的采样行为）。
 */
class ImageStoreTest {

    @Test
    fun `长边不超过上限时按原尺寸`() {
        assertEquals(800 to 600, ImageStore.targetSize(800, 600, ImageStore.MAX_EDGE))
        assertEquals(2048 to 1000, ImageStore.targetSize(2048, 1000, ImageStore.MAX_EDGE))
    }

    @Test
    fun `超过上限时按长边等比缩`() {
        // 竖图：高是长边
        assertEquals(1536 to 2048, ImageStore.targetSize(3000, 4000, ImageStore.MAX_EDGE))
        // 横图：宽是长边
        assertEquals(2048 to 1365, ImageStore.targetSize(4000, 2666, ImageStore.MAX_EDGE))
    }

    @Test
    fun `缩略图按同一个函数缩到三百二`() {
        assertEquals(320 to 240, ImageStore.targetSize(2048, 1536, ImageStore.THUMB_EDGE))
    }

    @Test
    fun `尺寸非法时不崩`() {
        assertEquals(0 to 0, ImageStore.targetSize(0, 0, ImageStore.MAX_EDGE))
        assertEquals(1, ImageStore.sampleSizeFor(0, 0, ImageStore.MAX_EDGE))
        assertEquals(1, ImageStore.sampleSizeFor(-5, 100, ImageStore.MAX_EDGE))
    }

    @Test
    fun `采样率让解码结果不超过上限`() {
        // 4 倍采样：8000/4 = 2000 <= 2048，而 8000/2 = 4000 太大
        assertEquals(4, ImageStore.sampleSizeFor(8000, 6000, ImageStore.MAX_EDGE))
        // 刚好一倍
        assertEquals(1, ImageStore.sampleSizeFor(2048, 1536, ImageStore.MAX_EDGE))
        assertEquals(2, ImageStore.sampleSizeFor(4096, 3072, ImageStore.MAX_EDGE))
        // 3000 只要采一次就进了上限（1500 < 2048），后面再精确缩到 1500
        assertEquals(2, ImageStore.sampleSizeFor(3000, 2000, ImageStore.MAX_EDGE))
    }
}
