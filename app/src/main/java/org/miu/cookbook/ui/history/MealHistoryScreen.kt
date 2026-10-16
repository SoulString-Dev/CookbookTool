package org.miu.cookbook.ui.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.miu.cookbook.R
import org.miu.cookbook.ui.components.EmptyState

/**
 * M0：只有空状态。
 * M4 会接上饮食历史列表（**正序排列但初始停在最下方**，向上拖动回溯历史）。
 */
@Composable
fun MealHistoryScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.DateRange,
        title = stringResource(R.string.history_empty_title),
        hint = stringResource(R.string.history_empty_hint),
        modifier = modifier,
    )
}
