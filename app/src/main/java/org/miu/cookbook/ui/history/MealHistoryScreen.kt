package org.miu.cookbook.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.components.EmptyState

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * 饮食历史页。
 *
 * 需求里说「默认正序、停在列表最下方、向上拖动回溯历史」——所以列表是
 * `reverseLayout = true` + 把天数倒着喂进去：index 0 就是最新的一天，落在屏幕底部。
 *
 * 进多选不再是浮动的编辑按钮，而是顶栏「⋮」菜单里的「多选」（[selectionRequested]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealHistoryScreen(
    onOpenRecipe: (Long) -> Unit,
    /** 顶栏「⋮」里点了「多选」。 */
    selectionRequested: Boolean = false,
    onSelectionRequestedHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()

    val rows by repository.mealHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val days = remember(rows) { rows.toHistoryDays(container.filesDir) }
    val listState = rememberLazyListState()

    // 弹窗里按 epochDay 现查最新的那一天：在里面删掉/挪走某顿之后，弹窗内容要跟着变，
    // 不能拿着打开那一刻的快照。整天被删光时 days 里查不到，弹窗自然就关了。
    var detailEpoch by remember { mutableStateOf<Long?>(null) }
    var editEpoch by remember { mutableStateOf<Long?>(null) }
    var deleteEpoch by remember { mutableStateOf<Long?>(null) }
    val detailDay = days.firstOrNull { it.epochDay == detailEpoch }
    val editDay = days.firstOrNull { it.epochDay == editEpoch }
    val deleteDay = days.firstOrNull { it.epochDay == deleteEpoch }
    var selecting by remember { mutableStateOf(false) }
    var selectedDays by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteSelection by remember { mutableStateOf(false) }

    LaunchedEffect(selectionRequested) {
        if (selectionRequested) {
            selecting = true
            selectedDays = emptySet()
            onSelectionRequestedHandled()
        }
    }

    // 安卓返回键：先退出多选，不要直接把应用丢到后台
    BackHandler(enabled = selecting) {
        selecting = false
        selectedDays = emptySet()
    }

    Box(modifier.fillMaxSize()) {
        if (days.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.DateRange,
                title = stringResource(R.string.history_empty_title),
                hint = stringResource(R.string.history_empty_hint),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 预留底部空白，免得最后一天被浮动加号挡住
                item(key = "bottom-space") { Spacer(Modifier.height(96.dp)) }
                // 多选时顶上会浮一条「已选 N 天 + 删除」，给它让点位置
                item(key = "top-space") { Spacer(Modifier.height(if (selecting) 72.dp else 8.dp)) }
                items(days.asReversed(), key = { it.epochDay }) { day ->
                    DayRow(
                        day = day,
                        selecting = selecting,
                        selected = day.epochDay in selectedDays,
                        onOpen = { detailEpoch = day.epochDay },
                        onToggleSelection = { selectedDays = selectedDays.toggle(day.epochDay) },
                        onEdit = { editEpoch = day.epochDay },
                        onDelete = { deleteEpoch = day.epochDay },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 108.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            if (selecting) {
                HistorySelectionBar(
                    count = selectedDays.size,
                    onCancel = {
                        selecting = false
                        selectedDays = emptySet()
                    },
                    onDelete = { if (selectedDays.isNotEmpty()) deleteSelection = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    detailDay?.let { day ->
        DayDetailDialog(
            day = day,
            onOpenRecipe = { recipeId ->
                detailEpoch = null
                onOpenRecipe(recipeId)
            },
            onDismiss = { detailEpoch = null },
        )
    }

    editDay?.let { day ->
        DayEditDialog(
            day = day,
            repository = repository,
            onDismiss = { editEpoch = null },
        )
    }

    deleteDay?.let { day ->
        DeleteMealDayDialog(
            day = day,
            onDismiss = { deleteEpoch = null },
            onConfirm = {
                deleteEpoch = null
                scope.launch { repository.deleteMealDay(day.epochDay) }
            },
        )
    }

    if (deleteSelection) {
        val ids = days.filter { it.epochDay in selectedDays }.flatMap { it.allLogIds }
        AlertDialog(
            onDismissRequest = { deleteSelection = false },
            title = { Text(stringResource(R.string.meal_history_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.meal_history_delete_message, selectedDays.size))
                    Text(
                        text = stringResource(R.string.delete_irreversible),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteSelection = false
                    selecting = false
                    selectedDays = emptySet()
                    scope.launch { repository.deleteMealLogs(ids) }
                }) {
                    Text(
                        text = stringResource(R.string.delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelection = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 历史列表的一行：左边年月日，右边上中下排早/午/晚（没记录的那一餐不占位）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DayRow(
    day: MealHistoryDay,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelection() else onOpen() },
                    onLongClick = { if (selecting) onToggleSelection() else menuOpen = true },
                )
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.width(76.dp)) {
                Text(
                    text = day.dayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = day.yearWeekLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                day.orderedMeals.forEach { (slot, entries) ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = slot.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(44.dp),
                        )
                        Text(
                            text = entries.joinToString("、") { it.title },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meal_day_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.meal_day_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** 多选时顶上那条：已选几天 + 删除。 */
@Composable
private fun HistorySelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.selection_exit),
                )
            }
            Text(
                text = if (count == 0) {
                    stringResource(R.string.meal_history_selection_none)
                } else {
                    stringResource(R.string.meal_history_selection_count, count)
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDelete, enabled = count > 0) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
