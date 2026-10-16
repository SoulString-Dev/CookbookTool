package org.miu.cookbook.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import org.miu.cookbook.R
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.domain.meal.MealTime

/**
 * 记录一顿：先挑日期和餐段，再确认。
 *
 * 默认值都来自「现在」：餐段按当前时段判，日期跟着餐段（只有凌晨的晚饭算前一天）。
 * 用户自己翻过日期之后，再改餐段就不再动日期了 —— 否则他刚翻到昨天又会被拽回今天。
 *
 * 这个弹窗里没有输入框，所以 Robolectric 下可以正常测试（有输入框的弹窗会永远 idle 不下来）。
 */
@Composable
fun RecordMealDialog(
    recipeTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (epochDay: Long, slot: MealSlot) -> Unit,
    /** 新建食谱之后弹这个窗时，多给一个「只保存，不记录」的出口。 */
    allowSkip: Boolean = false,
    onSkip: () -> Unit = {},
) {
    val now = remember { LocalDateTime.now() }
    var slot by remember { mutableStateOf(MealTime.resolve(now).slot) }
    var dateTouched by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(MealTime.dateFor(MealTime.resolve(now).slot, now)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_record_title)) },
        text = {
            Column {
                Text(
                    text = recipeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(12.dp))

                Text(
                    text = stringResource(R.string.meal_record_date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        date = date.minusDays(1)
                        dateTouched = true
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
                    }
                    Text(
                        text = "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = {
                        date = date.plusDays(1)
                        dateTouched = true
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
                    }
                }

                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.meal_record_slot),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == slot,
                            onClick = {
                                slot = candidate
                                if (!dateTouched) date = MealTime.dateFor(candidate, now)
                            },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(date.toEpochDay(), slot) }) {
                Text(stringResource(R.string.meal_record_confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                if (allowSkip) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.meal_record_keep_only))
                    }
                }
            }
        },
    )
}

/** 某道菜自己的饮食历史，按时间倒序（详情页「查看饮食历史」）。 */
@Composable
fun RecipeMealHistoryDialog(
    recipeId: Long,
    recipeTitle: String,
    repository: CookbookRepository,
    onDismiss: () -> Unit,
) {
    val rows by remember(recipeId) { repository.mealHistoryOf(recipeId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_history_of_recipe, recipeTitle)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.meal_history_empty))
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        val date = LocalDate.ofEpochDay(row.epochDay)
                        Text(
                            text = "${date.year}年${date.monthValue}月${date.dayOfMonth}日 · ${row.slot.label}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** 点某一天弹出来的浮动卡片：当天早/午/晚各吃了什么，点菜名能进详情。 */
@Composable
fun DayDetailDialog(
    day: MealHistoryDay,
    onOpenRecipe: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${day.yearWeekLabel} ${day.dayLabel}") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (day.orderedMeals.isEmpty()) {
                    Text(stringResource(R.string.meal_day_empty))
                }
                day.orderedMeals.forEach { (slot, entries) ->
                    Text(
                        text = slot.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    entries.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenRecipe(entry.recipeId) }
                                .padding(vertical = 6.dp),
                        ) {
                            MealThumb(entry.coverPath)
                            Spacer(Modifier.width(10.dp))
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * 编辑某一天的记录：把某一餐整体改到别的餐段（冲突就互换），或者删掉其中一道菜。
 *
 * 每顿都是「整份换」而不是逐条改时间，跟需求里说的「三顿饭的时间」是一回事：
 * 一顿饭里的几道菜本来就是同一顿饭。
 */
@Composable
fun DayEditDialog(
    day: MealHistoryDay,
    repository: CookbookRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var moveTarget by remember { mutableStateOf<MealSlot?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_day_edit)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.meal_move_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                day.orderedMeals.forEach { (slot, entries) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = slot.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            AssistChip(
                                onClick = { moveTarget = slot },
                                label = { Text(stringResource(R.string.meal_move_to)) },
                            )
                            DropdownMenu(
                                expanded = moveTarget == slot,
                                onDismissRequest = { moveTarget = null },
                            ) {
                                MealSlot.entries.filter { it != slot }.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.label) },
                                        onClick = {
                                            moveTarget = null
                                            scope.launch {
                                                repository.moveMealSlot(
                                                    day.epochDay,
                                                    slot,
                                                    target,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    entries.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                scope.launch { repository.deleteMealLogs(listOf(entry.logId)) }
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.meal_entry_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

/** 删掉一整天（长按菜单里的「删除」）。 */
@Composable
fun DeleteMealDayDialog(
    day: MealHistoryDay,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_day_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.meal_day_delete_message, day.logCount))
                Text(
                    text = stringResource(R.string.delete_irreversible),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 历史列表里的封面小图（没有就留个占位方块）。 */
@Composable
fun MealThumb(path: String?) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
