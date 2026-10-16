package org.miu.cookbook.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 用来弹一句短提示（"复制成功"、"两餐已经交换"、备份结果）。
 *
 * 不用系统 Toast：Toast 的样式是系统的，日间/夜间模式切不动，跟界面格格不入。
 * 这里走 Material 的 Snackbar，颜色字号都跟着应用主题走。
 */
@Stable
class Messenger(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /** 弹一句提示。上一条还没消失就先把它收掉，免得排队。 */
    fun show(message: String) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
}

/** 当前界面的提示通道。要在 CookbookRoot 里用 CompositionLocalProvider 注入。 */
val LocalMessenger = staticCompositionLocalOf<Messenger> {
    error("Messenger 未提供：请在 CookbookRoot 里用 CompositionLocalProvider 注入")
}

/** 造一个和 [SnackbarHostState] 绑定的 [Messenger]。 */
@Composable
fun rememberMessenger(hostState: SnackbarHostState): Messenger {
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { Messenger(hostState, scope) }
}
