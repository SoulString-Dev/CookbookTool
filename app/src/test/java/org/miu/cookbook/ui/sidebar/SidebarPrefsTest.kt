package org.miu.cookbook.ui.sidebar

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 侧栏偏好：只是这台设备自己的看法，存在 SharedPreferences 里。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SidebarPrefsTest {

    private fun prefs() = SidebarPrefs(ApplicationProvider.getApplicationContext())

    @Test
    fun `默认打开，而且只有全部一项`() {
        val prefs = prefs()
        assertTrue(prefs.enabled)
        assertEquals(emptyList<String>(), prefs.categoryUuids)
        assertEquals("", prefs.selectedUuid)
    }

    @Test
    fun `关掉之后再读还是关的`() {
        prefs().enabled = false
        assertFalse(prefs().enabled)
    }

    @Test
    fun `分类的顺序原样存下来`() {
        val uuids = listOf("b-uuid", "a-uuid", "c-uuid")
        prefs().categoryUuids = uuids
        assertEquals(uuids, prefs().categoryUuids)
    }

    @Test
    fun `清空分类以后读出来是空列表而不是一条空串`() {
        val prefs = prefs()
        prefs.categoryUuids = listOf("only")
        prefs.categoryUuids = emptyList()
        assertEquals(emptyList<String>(), prefs.categoryUuids)
    }

    @Test
    fun `选中状态也能存下`() {
        prefs().selectedUuid = "x-uuid"
        assertEquals("x-uuid", prefs().selectedUuid)
    }
}
