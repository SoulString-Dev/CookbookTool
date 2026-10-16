package org.miu.cookbook.ui.recipes.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.miu.cookbook.di.LocalAppContainer

/** 停手多久之后落盘。 */
const val AutoSaveDelayMillis = 400L

/**
 * 边编边存的公共实现：连续改动只在停手之后写一次，离开页面时把最后一下补上。
 *
 * 两个要点：
 * - 协程挂在 `AppContainer.appScope` 上，**不是**页面的 `rememberCoroutineScope`——
 *   后者随页面销毁一起取消，正要写的那一下会丢；
 * - 返回的 `schedule` 每次都用新的值覆盖待写内容，所以中途的改动会被最后一次取代。
 */
@Composable
fun <T> rememberDebouncedSaver(
    delayMillis: Long = AutoSaveDelayMillis,
    save: suspend (T) -> Unit,
): (T) -> Unit {
    val appScope = LocalAppContainer.current.appScope
    val saveState = rememberUpdatedState(save)
    var pending by remember { mutableStateOf<T?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            pending?.let { value ->
                pending = null
                appScope.launch { saveState.value(value) }
            }
        }
    }

    return remember {
        { value: T ->
            pending = value
            job?.cancel()
            job = appScope.launch {
                delay(delayMillis)
                pending = null
                saveState.value(value)
            }
        }
    }
}
