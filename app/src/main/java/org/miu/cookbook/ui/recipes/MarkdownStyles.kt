package org.miu.cookbook.ui.recipes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 正文区那几级标题的样式，展示态和编辑态共用一份。
 *
 * 之前界面上是把 Markdown 的 `#` / `##` 直接画出来当标题用，看着像没渲染的源码，
 * 而且三级标题的字号和正文一样大、反而显得更小。现在三级字号拉开：
 * 一级 28sp、二级 22sp、三级 18sp，正文 16sp。
 */
@Composable
fun Mdh1Style(): TextStyle = MaterialTheme.typography.headlineMedium.copy(
    fontWeight = FontWeight.SemiBold,
)

@Composable
fun Mdh2Style(): TextStyle = MaterialTheme.typography.titleLarge.copy(
    fontWeight = FontWeight.SemiBold,
)

@Composable
fun Mdh3Style(): TextStyle = MaterialTheme.typography.titleMedium.copy(
    fontSize = 18.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.SemiBold,
)

/**
 * 正文样式，**必须带上颜色**。
 *
 * `BasicTextField` 拿到一个没写颜色的 TextStyle 时会退回默认的黑色，
 * 夜间模式下就成了黑字压在深色背景上，所以这里显式取 `onSurface`。
 */
@Composable
fun MdBodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    color = MaterialTheme.colorScheme.onSurface,
)
