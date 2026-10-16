package org.miu.cookbook.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.CategoryDao
import org.miu.cookbook.data.db.MealLogDao
import org.miu.cookbook.data.db.RecipeDao
import org.miu.cookbook.data.db.RecipeImageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖容器，不引 Hilt：
 * 依赖就这几个，注解处理器的编译开销不值得。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val recipeDao: RecipeDao get() = database.recipeDao()
    val categoryDao: CategoryDao get() = database.categoryDao()
    val mealLogDao: MealLogDao get() = database.mealLogDao()
    val imageDao: RecipeImageDao get() = database.imageDao()

    val repository: CookbookRepository by lazy {
        CookbookRepository(database, filesDir)
    }

    /**
     * 活到进程结束的作用域。
     *
     * 编辑页现在是一边打字一边落盘（防抖 400ms），离开页面时必须再把最后一下补上；
     * 用页面自己的 [rememberCoroutineScope] 会因为页面销毁而被取消，所以放这里。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 图片存在应用私有目录，库里只记相对路径。 */
    val filesDir: java.io.File get() = appContext.filesDir
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供：请在 MainActivity 里用 CompositionLocalProvider 注入")
}
