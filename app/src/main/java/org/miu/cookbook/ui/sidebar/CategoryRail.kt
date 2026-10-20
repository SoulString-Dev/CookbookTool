package org.miu.cookbook.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.miu.cookbook.R

/** 侧栏的一项：分类的跨设备身份 + 给人看的名字。 */
data class RailCategory(val uuid: String, val name: String)

private val RailWidth = 104.dp

/** 名字太长时自己收：长于六个字就留五个字加省略号（分类名本来也不该长）。 */
fun railLabel(name: String): String = if (name.length > 6) name.take(5) + "…" else name

/**
 * 食谱页左侧那条分类栏。
 *
 * 它**附属于食谱页**（不是能从屏幕边缘拉出来的抽屉），所以不能滑开滑关，宽度也是固定的一小条。
 * 从上到下固定三段：全部 / 自定义分类（可滚动）/ 编辑分类。
 */
@Composable
fun CategoryRail(
    categories: List<RailCategory>,
    selectedUuid: String,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(RailWidth)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        RailItem(
            label = stringResource(R.string.rail_all),
            selected = selectedUuid.isEmpty(),
            onClick = { onSelect("") },
        )
        Spacer(Modifier.height(4.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            categories.forEach { category ->
                RailItem(
                    label = railLabel(category.name),
                    selected = category.uuid == selectedUuid,
                    onClick = { onSelect(category.uuid) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        RailItem(
            label = stringResource(R.string.category_edit_title),
            selected = false,
            onClick = onEdit,
        )
    }
}

@Composable
private fun RailItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp, horizontal = 2.dp),
        )
    }
}
