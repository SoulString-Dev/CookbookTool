package org.miu.cookbook.ui.recipes.edit

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.di.LocalAppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.miu.cookbook.domain.recipe.IngredientLine
import org.miu.cookbook.domain.recipe.Units
import kotlin.math.roundToInt

private val RowHeight = 76.dp

/** 停手多久之后落盘（和正文编辑器同一个节奏）。 */
private const val AutoSaveDelayMillis = 400L

/**
 * 原料编辑：一行三格（名字 / 数量 / 量词），按住左边的把手上下拖就能换顺序。
 *
 * 「食材数填了数字就默认补 g」这条规则在 [Units.defaultFor] 里。
 */
@Composable
fun IngredientEditScreen(
    recipeId: Long,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val detailFlow = remember(recipeId) { repository.recipeDetail(recipeId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    val lines = remember { mutableStateListOf<IngredientLine>() }
    var loaded by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var latestLines by remember { mutableStateOf<List<IngredientLine>>(emptyList()) }

    // 一边编一边落盘：停手 400ms 写一次，退出页面时把最后没来得及写的那一下补上
    fun scheduleSave() {
        val snapshot = lines.toList()
        latestLines = snapshot
        saveJob?.cancel()
        saveJob = container.appScope.launch {
            delay(AutoSaveDelayMillis)
            repository.setIngredients(recipeId, snapshot)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveJob?.cancel()
            val snapshot = latestLines
            if (snapshot.isNotEmpty()) {
                container.appScope.launch { repository.setIngredients(recipeId, snapshot) }
            }
        }
    }

    LaunchedEffect(detail) {
        val loadedDetail = detail
        if (!loaded && loadedDetail != null) {
            lines.addAll(loadedDetail.ingredients.sortedBy { it.sortOrder }
                .map { IngredientLine(name = it.name, amount = it.amount, unit = it.unit) })
            if (lines.isEmpty()) lines.add(IngredientLine())
            loaded = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 输入法弹出来时让出下半屏，正在编的那几行不会被键帽压住
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.ingredient_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            DragList(
                lines = lines,
                onMove = {
                    from, to ->
                    lines.add(to, lines.removeAt(from))
                    scheduleSave()
                },
                onEdited = { scheduleSave() },
            )
        }
    }
}

/** 可拖动排序的列表：高度固定，长按把手拖动，松手时按位移算落在第几行。 */
@Composable
private fun DragList(
    lines: MutableList<IngredientLine>,
    onMove: (from: Int, to: Int) -> Unit,
    onEdited: () -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column {
        lines.forEachIndexed { index, line ->
            val dragging = index == draggingIndex
            IngredientRow(
                line = line,
                dragging = dragging,
                onChange = {
                    lines[index] = it
                    onEdited()
                },
                onDelete = {
                    lines.removeAt(index)
                    onEdited()
                },
                dragHandleModifier = Modifier.pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingIndex = index
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            val target = (draggingIndex + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, lines.lastIndex)
                            if (target != draggingIndex) onMove(draggingIndex, target)
                            draggingIndex = -1
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            draggingIndex = -1
                            dragOffset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                        },
                    )
                },
                modifier = Modifier
                    .height(RowHeight)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
            )
        }
    }

    TextButton(onClick = {
        lines.add(IngredientLine())
        onEdited()
    }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.ingredient_add))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientRow(
    line: IngredientLine,
    dragging: Boolean,
    onChange: (IngredientLine) -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    var unitMenuOpen by remember { mutableStateOf(false) }

    Surface(
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            // 把手：只有它能发起拖动，免得在输入框里划一下就换了顺序
            Box(modifier = dragHandleModifier.padding(4.dp)) {
                Text(
                    text = "⠿",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = line.name,
                onValueChange = { onChange(line.copy(name = it)) },
                placeholder = { Text(stringResource(R.string.ingredient_name_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(6.dp))

            OutlinedTextField(
                value = line.amount,
                onValueChange = { amount ->
                    // 填了数字就顺手把量词补成 g
                    onChange(line.copy(amount = amount, unit = Units.defaultFor(amount, line.unit)))
                },
                placeholder = { Text(stringResource(R.string.ingredient_amount_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(84.dp),
            )

            Spacer(Modifier.width(6.dp))

            // 做成正经的下拉框：一眼能看出这里能点、点开是可选量词
            ExposedDropdownMenuBox(
                expanded = unitMenuOpen,
                onExpandedChange = { unitMenuOpen = it },
                modifier = Modifier.width(96.dp),
            ) {
                OutlinedTextField(
                    value = line.unit.ifEmpty { stringResource(R.string.ingredient_unit_none) },
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenuOpen) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = unitMenuOpen,
                    onDismissRequest = { unitMenuOpen = false },
                ) {
                    Units.COMMON.forEach { unit ->
                        DropdownMenuItem(
                            text = { Text(unit.ifEmpty { stringResource(R.string.ingredient_unit_none) }) },
                            onClick = {
                                onChange(line.copy(unit = unit))
                                unitMenuOpen = false
                            },
                        )
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 编辑类页面统一的顶栏：左取消、右完成。 */
