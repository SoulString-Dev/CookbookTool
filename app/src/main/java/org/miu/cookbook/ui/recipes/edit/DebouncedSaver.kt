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
 * 还没写进库的最后一下改动，按 key 暂存。
 *
 * 页面退出时补写是异步的：新页面可能先读库、拿到补写之前的旧数据，随后用户再一改，
 * 就把刚才那些字覆盖掉了（转屏时最容易撞上）。所以补写前先在这里记一笔，
 * 重进页面时优先拿它渲染；存完之后再抹掉，之后读库就是新的了。
 */
object PendingEdits {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun put(key: String, value: Any) {
        entries[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = entries[key] as? T

    fun clearIf(key: String, value: Any) {
        entries.remove(key, value)
    }
}

/**
 * 边编边存的公共实现：连续改动只在停手之后写一次，离开页面时把最后一下补上。
 *
 * 三个要点：
 * - 协程挂在 `AppContainer.appScope` 上，**不是**页面的 `rememberCoroutineScope`——
 *   后者随页面销毁一起取消，正要写的那一下会丢；
 * - 返回的 `schedule` 每次都用新的值覆盖待写内容，所以中途的改动会被最后一次取代；
 * - 给了 [key] 的话，退出时那一下会先记进 [PendingEdits]，重进同一页先按它渲染，
 *   不会出现「读到旧库 → 用户接着改 → 旧内容盖掉新内容」。
 */
@Composable
fun <T> rememberDebouncedSaver(
    key: String? = null,
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
                if (key != null) PendingEdits.put(key, value as Any)
                appScope.launch {
                    saveState.value(value)
                    if (key != null) PendingEdits.clearIf(key, value as Any)
                }
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
                if (key != null) PendingEdits.clearIf(key, value as Any)
            }
        }
    }
}
