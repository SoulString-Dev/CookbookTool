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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.miu.cookbook.R
import org.miu.cookbook.di.LocalAppContainer
import kotlinx.coroutines.delay
import org.miu.cookbook.domain.recipe.IngredientLine
import org.miu.cookbook.domain.recipe.Units
import kotlin.math.roundToInt

private val RowHeight = 76.dp

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
    // 每一行一个稳定 id：删掉中间一行时，后面的行不会被当成「同一个位置」而复用
    // 还没关的量词下拉（以前会出现下拉跳到下一行）
    val rowIds = remember { mutableStateListOf<Long>() }
    var nextRowId by remember { mutableStateOf(0L) }
    var loaded by remember { mutableStateOf(false) }
    val saveKey = "ingredients:$recipeId"
    // 一边编一边落盘：停手 400ms 写一次，退出页面时把最后没来得及写的那一下补上
    val scheduleSave = rememberDebouncedSaver<List<IngredientLine>>(key = saveKey) { snapshot ->
        repository.setIngredients(recipeId, snapshot)
    }

    fun replaceLines(next: List<IngredientLine>) {
        lines.clear()
        rowIds.clear()
        val source = next.ifEmpty { listOf(IngredientLine()) }
        source.forEach {
            lines.add(it)
            rowIds.add(nextRowId++)
        }
    }

    // 刚退出又进来：先用还没写完的那一份渲染，别让库里的旧内容顶掉（转屏时最明显）
    LaunchedEffect(saveKey) {
        val pending = PendingEdits.peek<List<IngredientLine>>(saveKey) ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        replaceLines(pending)
        loaded = true
    }

    LaunchedEffect(detail) {
        val loadedDetail = detail
        if (!loaded && loadedDetail != null) {
            replaceLines(
                loadedDetail.ingredients.sortedBy { it.sortOrder }
                    .map { IngredientLine(name = it.name, amount = it.amount, unit = it.unit) },
            )
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
                rowIds = rowIds,
                newRowId = { nextRowId++ },
                onMove = { from, to ->
                    lines.add(to, lines.removeAt(from))
                    rowIds.add(to, rowIds.removeAt(from))
                    scheduleSave(lines.toList())
                },
                onEdited = { scheduleSave(lines.toList()) },
            )
        }
    }
}

/** 可拖动排序的列表：高度固定，长按把手拖动，松手时按位移算落在第几行。 */
@Composable
private fun DragList(
    lines: MutableList<IngredientLine>,
    rowIds: MutableList<Long>,
    newRowId: () -> Long,
    onMove: (from: Int, to: Int) -> Unit,
    onEdited: () -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column {
        lines.forEachIndexed { index, line ->
            val dragging = index == draggingIndex
            key(rowIds.getOrNull(index) ?: index.toLong()) {
            IngredientRow(
                line = line,
                dragging = dragging,
                onChange = {
                    lines[index] = it
                    onEdited()
                },
                onDelete = {
                    lines.removeAt(index)
                    if (index < rowIds.size) rowIds.removeAt(index)
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
    }

    TextButton(onClick = {
        lines.add(IngredientLine())
        rowIds.add(newRowId())
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
            val handleLabel = stringResource(R.string.ingredient_drag_handle)
            Box(
                modifier = dragHandleModifier
                    .padding(4.dp)
                    .semantics { contentDescription = handleLabel },
            ) {
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

