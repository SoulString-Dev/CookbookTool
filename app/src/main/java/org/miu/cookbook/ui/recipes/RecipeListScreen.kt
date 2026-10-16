package org.miu.cookbook.ui.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.domain.search.FuzzySearch
import org.miu.cookbook.ui.components.EmptyState

/** 顶部那条浮动栏（搜索或多选）的高度，列表要给它让出位置。 */
private val TopBarArea = 80.dp

/**
 * 食谱列表页：
 * 顶部浮动搜索栏（右侧编辑按钮进多选），列表项点开详情、长按弹菜单，
 * 多选时可以批量设分类或删除。
 */
@Composable
fun RecipeListScreen(
    onOpenRecipe: (Long) -> Unit,
    onEditRecipe: (Long) -> Unit,
    newRecipeRequested: Boolean,
    onNewRecipeHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()

    val rows by repository.recipeList.collectAsStateWithLifecycle(initialValue = emptyList())
    val allItems = remember(rows) { rows.map { it.toListItem(container.filesDir) } }

    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var deleteTargets by remember { mutableStateOf<List<Long>?>(null) }
    var categoryTargets by remember { mutableStateOf<List<Long>?>(null) }
    var showNewRecipe by remember { mutableStateOf(false) }

    val visible = remember(allItems, query) { FuzzySearch.rank(allItems, query) { it.searchDocument } }

    // 右下角加号（在 CookbookRoot 里）请求新建食谱
    LaunchedEffect(newRecipeRequested) {
        if (newRecipeRequested) {
            showNewRecipe = true
            onNewRecipeHandled()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            allItems.isEmpty() -> EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.recipes_empty_title),
                hint = stringResource(R.string.recipes_empty_hint),
                modifier = Modifier.align(Alignment.Center),
            )

            visible.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.search_no_result_title, query),
                hint = stringResource(R.string.search_no_result_hint),
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = TopBarArea, bottom = 96.dp),
            ) {
                if (query.isNotBlank()) {
                    item(key = "count") {
                        Text(
                            text = stringResource(R.string.search_result_count, visible.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                        )
                    }
                }
                items(visible, key = { it.id }) { item ->
                    RecipeListRowItem(
                        item = item,
                        selecting = selecting,
                        selected = item.id in selectedIds,
                        onOpen = { onOpenRecipe(item.id) },
                        onToggleSelection = { selectedIds = selectedIds.toggle(item.id) },
                        onEditRecipe = { onEditRecipe(item.id) },
                        onEditCategories = { categoryTargets = listOf(item.id) },
                        onDelete = { deleteTargets = listOf(item.id) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 102.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }

        if (allItems.isNotEmpty()) {
            if (selecting) {
                SelectionBar(
                    count = selectedIds.size,
                    onCancel = {
                        selecting = false
                        selectedIds = emptySet()
                    },
                    onSetCategories = {
                        if (selectedIds.isNotEmpty()) categoryTargets = selectedIds.toList()
                    },
                    onDelete = {
                        if (selectedIds.isNotEmpty()) deleteTargets = selectedIds.toList()
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
                RecipeSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onStartSelection = { selecting = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    if (showNewRecipe) {
        NewRecipeDialog(
            onDismiss = { showNewRecipe = false },
            onCreate = { title ->
                showNewRecipe = false
                scope.launch { onOpenRecipe(repository.createRecipe(title)) }
            },
        )
    }

    deleteTargets?.let { ids ->
        DeleteRecipesDialog(
            recipeIds = ids,
            title = allItems.firstOrNull { it.id == ids.first() }?.title,
            repository = repository,
            onDismiss = { deleteTargets = null },
            onConfirm = {
                deleteTargets = null
                scope.launch {
                    repository.deleteRecipes(ids)
                    selectedIds = selectedIds - ids.toSet()
                    if (selectedIds.isEmpty()) selecting = false
                }
            },
        )
    }

    categoryTargets?.let { ids ->
        CategoryPickerDialog(
            recipeIds = ids,
            repository = repository,
            onDismiss = { categoryTargets = null },
            onApplied = { categoryTargets = null },
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/** 浮在列表上方的搜索栏：左边搜索框，右边进多选的编辑按钮。 */
@Composable
private fun RecipeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onStartSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_clear),
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onStartSelection) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.action_start_selection),
                )
            }
        }
    }
}

/** 多选时顶部的栏：退出、已选数量、批量设分类、批量删除。 */
@Composable
private fun SelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onSetCategories: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 12.dp)) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.selection_exit),
                )
            }
            Text(
                text = if (count == 0) {
                    stringResource(R.string.selection_none)
                } else {
                    stringResource(R.string.selection_count, count)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSetCategories, enabled = count > 0) {
                Text(stringResource(R.string.selection_set_categories))
            }
            TextButton(onClick = onDelete, enabled = count > 0) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DeleteRecipesDialog(
    recipeIds: List<Long>,
    title: String?,
    repository: CookbookRepository,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val mealLogCount by produceState(initialValue = 0, key1 = recipeIds) {
        value = repository.countMealLogs(recipeIds)
    }
    val single = recipeIds.size == 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column {
                Text(
                    text = if (single && title != null) {
                        stringResource(R.string.delete_single_message, title)
                    } else {
                        stringResource(R.string.delete_batch_message, recipeIds.size)
                    },
                )
                if (mealLogCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (single) {
                            stringResource(R.string.delete_meal_log_note, mealLogCount)
                        } else {
                            stringResource(R.string.delete_meal_log_note_batch, mealLogCount)
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.delete_irreversible),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
