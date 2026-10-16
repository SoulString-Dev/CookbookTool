package org.miu.cookbook.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.miu.cookbook.di.LocalAppContainer
import kotlinx.coroutines.launch
import org.miu.cookbook.R

/**
 * 分类编辑：
 * - 单个食谱：下面列出所有分类，勾上就在上面显示（勾选状态就是它的分类），可以取消勾选，也可以新建。
 * - 批量：所有分类默认都不勾（避免把「没勾的」当成要删的），确定时只往上加。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerDialog(
    recipeIds: List<Long>,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    // 数据源统一从 LocalAppContainer 取，和别的界面保持一致（不再一层层传 repository）
    val repository = LocalAppContainer.current.repository
    val categories by repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val single = recipeIds.size == 1
    val scope = rememberCoroutineScope()

    var selected by remember(recipeIds) { mutableStateOf(emptySet<Long>()) }
    var loaded by remember(recipeIds) { mutableStateOf(!single) }
    var newName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(recipeIds) {
        if (single) {
            selected = repository.linkedCategoryIds(recipeIds.first())
            loaded = true
        }
    }

    fun create() {
        val name = newName.trim()
        if (name.isEmpty() || working) return
        working = true
        scope.launch {
            try {
                val id = repository.ensureCategory(name)
                selected = selected + id
                newName = ""
            } finally {
                // 失败也得把按钮放开，不然这个弹窗就再也点不动了
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (single) {
                    stringResource(R.string.category_picker_title_single)
                } else {
                    stringResource(R.string.category_picker_title_batch, recipeIds.size)
                }
            )
        },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.category_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (!single) {
                        Text(
                            text = stringResource(R.string.category_picker_hint_batch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { category ->
                            val checked = category.id in selected
                            FilterChip(
                                selected = checked,
                                onClick = {
                                    selected = if (checked) {
                                        selected - category.id
                                    } else {
                                        selected + category.id
                                    }
                                },
                                label = { Text(category.name) },
                                leadingIcon = if (checked) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.category_new_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { create() }, enabled = newName.isNotBlank() && !working) {
                        Text(stringResource(R.string.category_new_action))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded,
                onClick = {
                    scope.launch {
                        if (single) {
                            repository.setCategories(recipeIds.first(), selected)
                        } else {
                            repository.addCategories(recipeIds, selected)
                        }
                        onApplied()
                    }
                },
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
