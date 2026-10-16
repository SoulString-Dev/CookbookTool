package org.miu.cookbook.ui.recipes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.miu.cookbook.R
import org.miu.cookbook.ui.components.EmptyState

/**
 * M0：只有空状态。
 * M1 会接上 Room 的食谱流、模糊搜索栏、长按菜单与批量多选。
 */
@Composable
fun RecipeListScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.AutoMirrored.Filled.List,
        title = stringResource(R.string.recipes_empty_title),
        hint = stringResource(R.string.recipes_empty_hint),
        modifier = modifier,
    )
}
