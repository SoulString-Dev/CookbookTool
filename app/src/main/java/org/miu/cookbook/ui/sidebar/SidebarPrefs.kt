package org.miu.cookbook.ui.sidebar

import android.content.Context

/**
 * 分类侧栏的本地偏好。
 *
 * 这些东西**故意不入库、也不同步**：侧栏长什么样是这台设备的看法，换台设备各看各的才正常。
 * 分类按 uuid 记（不是本机自增 id），所以导入备份换了库以后侧栏还在，同步回来的分类也对得上号。
 * 存的只是一串 uuid，分类被删掉以后由界面和同步完成时剪掉（[SidebarPrefs.categoryUuids] 里
 * 找不到对应分类的那些不会显示）。
 */
class SidebarPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 侧栏里显示哪些分类、按什么顺序（都是 uuid，按显示顺序）。 */
    var categoryUuids: List<String>
        get() = prefs.getString(KEY_CATEGORIES, "").orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CATEGORIES, value.joinToString(SEPARATOR)).apply()

    /** 当前高亮的是哪个分类；空串 = 「全部」。 */
    var selectedUuid: String
        get() = prefs.getString(KEY_SELECTED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    private companion object {
        const val PREF_NAME = "sidebar"
        const val KEY_ENABLED = "enabled"
        const val KEY_CATEGORIES = "categories"
        const val KEY_SELECTED = "selected"
        const val SEPARATOR = "\n"
    }
}
