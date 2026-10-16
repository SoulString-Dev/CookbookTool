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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.ui.components.LocalMessenger
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.domain.meal.MealTime

/** 日期显示成「2025年3月5日」。 */
private fun LocalDate.chineseText(): String = "${year}年${monthValue}月${dayOfMonth}日"

private fun epochDayOf(date: LocalDate): Long = date.toEpochDay()

/**
 * 日历选日期。
 *
 * 左右箭头翻页只能一天天挪，跨月跨年很烦，所以另外给一个日历弹窗。
 * `showModeToggle = false`：不要那个「手动输入」模式，免得弹窗里出现文本输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = epochDayOf(initial) * MILLIS_PER_DAY
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis ?: initialMillis
                onPick(LocalDate.ofEpochDay(millis / MILLIS_PER_DAY))
            }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 「哪一天 · 哪一餐」的选择器：日期可以按天翻、也可以点开日历挑，再加上早/午/晚三个 chip。
 *
 * 记录一顿和「把某顿改到别的日子」都用这一块，省得两处各写一遍。
 */
@Composable
private fun MealDaySlotPicker(
    date: LocalDate,
    slot: MealSlot,
    onDateChange: (LocalDate) -> Unit,
    onSlotChange: (MealSlot) -> Unit,
) {
    var pickingDate by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.meal_record_date),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onDateChange(date.minusDays(1)) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.meal_date_prev),
            )
        }
        // 点中间那个日期直接开日历，比一天天翻快得多
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { pickingDate = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = stringResource(R.string.action_pick_date),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = date.chineseText(), style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = { onDateChange(date.plusDays(1)) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.meal_date_next),
            )
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
                onClick = { onSlotChange(candidate) },
                label = { Text(candidate.label) },
            )
        }
    }

    if (pickingDate) {
        MealDatePickerDialog(
            initial = date,
            onPick = {
                onDateChange(it)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/**
 * 记录一顿：先挑日期和餐段，再确认。
 *
 * 默认值都来自「现在」：餐段按当前时段判，日期跟着餐段（只有凌晨的晚饭算前一天）。
 * 用户自己翻过日期（或从日历里挑过）之后，再改餐段就不再动日期了 ——
 * 否则他刚翻到昨天又会被拽回今天。
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
                MealDaySlotPicker(
                    date = date,
                    slot = slot,
                    onDateChange = {
                        date = it
                        dateTouched = true
                    },
                    onSlotChange = {
                        slot = it
                        if (!dateTouched) date = MealTime.dateFor(it, now)
                    },
                )
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

/**
 * 某道菜自己的饮食历史（详情页「查看饮食历史」）。
 *
 * 正序排（早的在上），但**打开就停在最下面**：最新的一条立刻能看到，
 * 想看更早的往上拖。跟饮食历史主界面是同一个手感。
 */
@Composable
fun RecipeMealHistoryDialog(
    recipeId: Long,
    recipeTitle: String,
    repository: CookbookRepository,
    onDismiss: () -> Unit,
) {
    val rows by remember(recipeId) { repository.mealHistoryOf(recipeId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()

    // 等第一帧把列表铺好再滚到底，否则 scrollToItem 找不到目标
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_history_of_recipe, recipeTitle)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.meal_history_empty))
            } else {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                    items(rows, key = { it.logId }) { row ->
                        Text(
                            text = "${LocalDate.ofEpochDay(row.epochDay).chineseText()} · ${row.slot.label}",
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
 * 编辑某一天的记录：把某一餐**改到任意一天的任意一餐**（记错日期是最常见的手滑），
 * 或者删掉其中一道菜。
 *
 * 一顿饭里的几道菜永远是整份一起动；目标那一餐如果已经有记录，就并进去，不覆盖也不交换。
 */
@Composable
fun DayEditDialog(
    day: MealHistoryDay,
    repository: CookbookRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<MealSlot?>(null) }
    val fromDate = LocalDate.ofEpochDay(day.epochDay)

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
                        AssistChip(
                            onClick = { moving = slot },
                            label = { Text(stringResource(R.string.meal_move_to)) },
                        )
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

    val messenger = LocalMessenger.current
    val swappedMessage = stringResource(R.string.meal_swapped)
    moving?.let { fromSlot ->
        MoveMealDialog(
            fromDate = fromDate,
            fromSlot = fromSlot,
            // 默认还是这一餐：只换个日子的话，用户什么都不用改；要换餐段再自己点
            defaultDate = fromDate,
            defaultSlot = fromSlot,
            onConfirm = { toDate, toSlot ->
                moving = null
                if (epochDayOf(toDate) != day.epochDay || toSlot != fromSlot) {
                    scope.launch {
                        val swapped = repository.moveMealSlotTo(
                            fromDay = day.epochDay,
                            fromSlot = fromSlot,
                            toDay = epochDayOf(toDate),
                            toSlot = toSlot,
                        )
                        if (swapped) messenger.show(swappedMessage)
                    }
                }
            },
            onDismiss = { moving = null },
        )
    }
}

/** 「这三道菜改到哪一天哪一餐」的小弹窗。 */
@Composable
private fun MoveMealDialog(
    fromDate: LocalDate,
    fromSlot: MealSlot,
    defaultDate: LocalDate,
    defaultSlot: MealSlot,
    onConfirm: (toDate: LocalDate, toSlot: MealSlot) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(defaultDate) }
    var slot by remember { mutableStateOf(defaultSlot) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_move_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.meal_move_from, fromDate.chineseText(), fromSlot.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                MealDaySlotPicker(
                    date = date,
                    slot = slot,
                    onDateChange = { date = it },
                    onSlotChange = { slot = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(date, slot) }) {
                Text(stringResource(R.string.meal_move_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
