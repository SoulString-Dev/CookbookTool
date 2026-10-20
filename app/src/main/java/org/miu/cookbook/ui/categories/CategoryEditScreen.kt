package org.miu.cookbook.ui.categories

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.miu.cookbook.R
import org.miu.cookbook.ui.sidebar.RailCategory
import kotlin.math.roundToInt

private val RowHeight = 52.dp
private val RowGap = 8.dp

/**
 * 编辑侧栏里放哪些分类、按什么顺序。
 *
 * 上半部分是库里**所有**分类（勾选表示在侧栏里出现），下半部分是已选的、可以长按拖动排序的列表。
 * 上下两处改的是同一份顺序：勾上就追加到末尾，取消勾选或按 × 就从列表里去掉。
 * 这个顺序只是本机怎么看，不改分类自己的 `sort_order`（那个会跟着同步走）。
 */
@Composable
fun CategoryEditScreen(
    allCategories: List<RailCategory>,
    selectedUuids: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var order by remember { mutableStateOf(selectedUuids) }
    val byUuid = remember(allCategories) { allCategories.associateBy { it.uuid } }
    val chosen = order.mapNotNull { byUuid[it] }

    fun commit(next: List<String>) {
        if (next == order) return
        order = next
        onChange(next)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.category_edit_all_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.category_edit_all_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (allCategories.isEmpty()) {
            Text(
                text = stringResource(R.string.category_edit_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            allCategories.forEach { category ->
                val checked = category.uuid in order
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            commit(if (on) order + category.uuid else order - category.uuid)
                        },
                    )
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = stringResource(R.string.category_edit_selected_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.category_edit_selected_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (chosen.isEmpty()) {
            Text(
                text = stringResource(R.string.category_edit_selected_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        } else {
            SelectedList(
                items = chosen,
                onReorder = { from, to ->
                    val next = order.toMutableList()
                    val moved = next.removeAt(from)
                    next.add(to, moved)
                    commit(next)
                },
                onRemove = { uuid -> commit(order - uuid) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 已选分类的拖动列表。
 *
 * 跟图片编辑页那套一样：拖动中**不改数据**、只改视觉位移（这样 [pointerInput] 的 key 不变，
 * 手势不会被重组打断），松手才按位移算落点；其余行让开位置，能看清会落到哪。
 */
@Composable
private fun SelectedList(
    items: List<RailCategory>,
    onReorder: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val stride = RowHeight + RowGap
    val stridePx = with(LocalDensity.current) { stride.toPx() }

    val from = dragIndex
    val target = from?.let { (it + (dragOffset / stridePx).roundToInt()).coerceIn(0, items.lastIndex) }

    fun slotOf(index: Int): Int = when {
        from == null || target == null -> index
        index == from -> target
        from < target && index in (from + 1)..target -> index - 1
        target < from && index in target until from -> index + 1
        else -> index
    }

    val removeLabel = stringResource(R.string.category_edit_remove)
    val dragLabel = stringResource(R.string.category_edit_drag)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stride * (items.size - 1) + RowHeight),
    ) {
        items.forEachIndexed { index, item ->
            val dragging = index == from
            val y = if (dragging) index * stridePx + dragOffset else slotOf(index) * stridePx
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (dragging) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RowHeight)
                    .graphicsLayer {
                        translationY = y
                        shadowElevation = if (dragging) 10.dp.toPx() else 0f
                    }
                    .zIndex(if (dragging) 2f else 1f)
                    .pointerInput(index, items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val landing = (index + (dragOffset / stridePx).roundToInt())
                                    .coerceIn(0, items.lastIndex)
                                dragIndex = null
                                dragOffset = 0f
                                if (landing != index) onReorder(index, landing)
                            },
                            onDragCancel = {
                                dragIndex = null
                                dragOffset = 0f
                            },
                        )
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_drag_indicator),
                        contentDescription = dragLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemove(item.uuid) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = removeLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
