package org.miu.cookbook.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import org.miu.cookbook.R

/** 设置页里可以选的三种外观。 */
enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/**
 * 主题模式存 SharedPreferences。
 *
 * 为这一个开关引 DataStore 不划算（多一个依赖、多一套协程 API），
 * SharedPreferences 读一次就是同步的，正好够用。
 */
object ThemePreferences {

    private const val FILE = "cookbook-ui"
    private const val KEY = "theme_mode"

    fun load(context: Context): ThemeMode = ThemeMode.fromKey(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, mode.name)
            .apply()
    }
}
