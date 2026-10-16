package org.miu.cookbook.ui.recipes.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 子编辑器（原料 / 做法备注）报给常驻顶栏的两个动作。
 *
 * 那两个页面以前自带一条「取消 / 完成」栏，跟顶栏叠在一起就是两条栏。现在顶栏常驻，
 * 动作也由顶栏来画，页面把「取消做什么、完成做什么」挂到这里就行。
 */
@Stable
class EditorTopBarActions {
    var cancel: (() -> Unit)? by mutableStateOf(null)
    var done: (() -> Unit)? by mutableStateOf(null)
}
