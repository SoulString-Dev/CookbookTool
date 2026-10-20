package org.miu.cookbook.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.domain.search.FuzzySearch
import org.miu.cookbook.ui.sidebar.CategoryRail
import org.miu.cookbook.ui.sidebar.RailCategory
import org.miu.cookbook.ui.components.EmptyState
import org.miu.cookbook.ui.history.MealThumb
import org.miu.cookbook.ui.history.RecordMealDialog

/** 顶部那条浮动栏（搜索或多选）的高度，列表要给它让出位置。 */
private val TopBarArea = 80.dp

/**
 * 食谱列表页：
 * 顶部浮动搜索栏，列表项点开详情、长按弹菜单，多选时可以批量设分类或删除。
 *
 * 进多选不再是搜索栏上的编辑图标，而是顶栏「⋮」菜单里的「多选」（[selectionRequested]）。
 */
@Composable
fun RecipeListScreen(
    onOpenRecipe: (Long) -> Unit,
    onEditRecipe: (Long) -> Unit,
    newRecipeRequested: Boolean,
    onNewRecipeHandled: () -> Unit,
    /** 右下角加号在非编辑态按下的第二种含义：记录今天吃了什么。 */
    recordRequested: Boolean = false,
    onRecordRequestedHandled: () -> Unit = {},
    /** 把「现在在不在记录模式」告诉外面，让右下角加号知道自己是哪一个含义。 */
    onRecordingChanged: (Boolean) -> Unit = {},
    /** 顶栏「⋮」里点了「多选」。 */
    selectionRequested: Boolean = false,
    onSelectionRequestedHandled: () -> Unit = {},
    /** 左边那条分类栏（设置里可以关掉）。 */
    railEnabled: Boolean = false,
    railCategories: List<RailCategory> = emptyList(),
    /** 当前置顶的分类 uuid；空串表示「全部」。 */
    railSelectedUuid: String = "",
    onRailSelect: (String) -> Unit = {},
    onEditCategories: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val rows by repository.recipeList.collectAsStateWithLifecycle(initialValue = emptyList())
    val conflictCount by repository.conflictCopies.collectAsStateWithLifecycle(initialValue = 0)
    val allItems = remember(rows) { rows.map { it.toListItem(container.filesDir) } }

    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var deleteTargets by remember { mutableStateOf<List<Long>?>(null) }
    var categoryTargets by remember { mutableStateOf<List<Long>?>(null) }
    var newRecipeRequest by remember { mutableStateOf<NewRecipeRequest?>(null) }

    // 记录模式：50% 黑蒙版 + 顶上一条搜索栏，点候选食谱弹「记下这一顿」
    var recording by remember { mutableStateOf(false) }
    var recordQuery by remember { mutableStateOf("") }
    var recordTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var recordTargetAllowSkip by remember { mutableStateOf(false) }

    // 两个口径（用户定的）：
    // - 不搜索时，选了分类就**只看这个分类**的菜；
    // - 搜索时不过滤，而是把带这个分类的排到最前、其余照旧按匹配度跟在后面。
    val visible = remember(allItems, query, railSelectedUuid) {
        val ranked = FuzzySearch.rank(allItems, query) { it.searchDocument }
        if (query.isBlank()) ranked.onlyInCategory(railSelectedUuid) else ranked.boostCategory(railSelectedUuid)
    }

    // 右下角加号（在 CookbookRoot 里）请求新建食谱
    LaunchedEffect(newRecipeRequested) {
        if (newRecipeRequested) {
            // 记录模式下再按加号 = 先新增食谱，然后接着确认记录
            newRecipeRequest = NewRecipeRequest(
                initialTitle = if (recording) recordQuery else query,
                thenRecord = recording,
            )
            onNewRecipeHandled()
        }
    }

    LaunchedEffect(recording) { onRecordingChanged(recording) }

    // 顶栏「⋮」→「多选」：等价于以前搜索栏上的编辑按钮
    LaunchedEffect(selectionRequested) {
        if (selectionRequested) {
            selecting = true
            selectedIds = emptySet()
            onSelectionRequestedHandled()
        }
    }

    // 加号在非编辑态的另一层含义：进记录模式
    LaunchedEffect(recordRequested) {
        if (recordRequested) {
            recording = true
            recordQuery = ""
            onRecordRequestedHandled()
        }
    }

    // 安卓返回键：先按「记录 → 多选 → 搜索」的顺序收摊，不要一按就退到桌面
    BackHandler(enabled = recording || selecting || query.isNotEmpty()) {
        when {
            recording -> recording = false
            selecting -> {
                selecting = false
                selectedIds = emptySet()
            }

            else -> {
                query = ""
                focusManager.clearFocus()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = modifier.fillMaxSize()) {
            if (railEnabled) {
                CategoryRail(
                    categories = railCategories,
                    selectedUuid = railSelectedUuid,
                    onSelect = onRailSelect,
                    onEdit = onEditCategories,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    allItems.isEmpty() -> EmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = stringResource(R.string.recipes_empty_title),
                        hint = stringResource(R.string.recipes_empty_hint),
                        modifier = Modifier.align(Alignment.Center),
                    )

                    // 按分类看的时候空了，说的得是分类的事，别拿「没搜到」去糊人
                    visible.isEmpty() && query.isBlank() -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.rail_empty_title),
                        hint = stringResource(R.string.rail_empty_hint),
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
                        // 冲突副本提示：同步时两边都改了同一道菜才会出现，点不掉，删掉副本它自己消失
                        if (conflictCount > 0 && query.isBlank()) {
                            item(key = "conflicts") { ConflictBanner(count = conflictCount) }
                        }
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

                if (allItems.isNotEmpty() && !recording) {
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
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }

        if (recording) {
            // 50% 黑蒙版：点空白处退出记录
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { recording = false },
            )
            RecordPanel(
                query = recordQuery,
                onQueryChange = { recordQuery = it },
                candidates = remember(allItems, recordQuery) {
                    FuzzySearch.rank(allItems, recordQuery) { it.searchDocument }
                },
                onExit = { recording = false },
                onPick = { item ->
                    recordTargetAllowSkip = false
                    recordTarget = item.id to item.title
                },
                onAddRecipe = { newRecipeRequest = NewRecipeRequest(recordQuery, thenRecord = true) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    // 一个入口：搜索栏（记录模式下是记录面板的搜索框）里有字就拿它当菜名，省得再打一遍。
    // 从记录模式进来的，建完接着问「记下这一顿」，也可以只保存不记录。
    newRecipeRequest?.let { request ->
        NewRecipeDialog(
            initialTitle = request.initialTitle,
            onDismiss = { newRecipeRequest = null },
            onCreate = { title ->
                newRecipeRequest = null
                scope.launch {
                    val newId = repository.createRecipe(title)
                    if (request.thenRecord) {
                        recordTargetAllowSkip = true
                        recordTarget = newId to title
                    } else {
                        onOpenRecipe(newId)
                    }
                }
            },
        )
    }

    recordTarget?.let { (recipeId, title) ->
        RecordMealDialog(
            recipeTitle = title,
            allowSkip = recordTargetAllowSkip,
            onDismiss = {
                recordTarget = null
                recording = false
            },
            onSkip = {
                recordTarget = null
                recording = false
            },
            onConfirm = { epochDay, slot ->
                recordTarget = null
                recording = false
                recordQuery = ""
                scope.launch { repository.logMeal(recipeId, epochDay, slot) }
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
            onDismiss = { categoryTargets = null },
            onApplied = { categoryTargets = null },
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * 「新建食谱」弹窗的一次请求。
 *
 * [initialTitle] 是搜索栏里已经打了一半的名字（记录模式下用记录面板的搜索框）；
 * [thenRecord] 表示建完要接着问「记下这一顿」——记录模式里按加号进来的都是这种。
 */
private data class NewRecipeRequest(val initialTitle: String, val thenRecord: Boolean)

/** 浮在列表上方的搜索栏。多选入口已经收进顶栏的「⋮」菜单，这里只剩搜索。 */
@Composable
private fun RecipeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
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
            modifier = Modifier.fillMaxWidth(),
        )
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
    // 用 remember + LaunchedEffect 而不是 produceState：lint 的 ProduceStateDoesNotAssignValue
    // 会把委托写法里的赋值看成误报，这里绕开它，行为一样
    var mealLogCount by remember(recipeIds) { mutableIntStateOf(0) }
    LaunchedEffect(recipeIds) { mealLogCount = repository.countMealLogs(recipeIds) }
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

/**
 * 记录模式顶上那一块：自动聚焦的搜索框、候选食谱、以及「新建食谱并记录」的入口。
 *
 * 点击落在面板之外的蒙版上会退出记录（蒙版本身是可点的）。
 */
@Composable
private fun RecordPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    candidates: List<RecipeListItem>,
    onExit: () -> Unit,
    onPick: (RecipeListItem) -> Unit,
    onAddRecipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 这个是「退出记录模式」，所以用返回箭头而不是 ×：× 会让人当成本地清除输入。
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.meal_record_exit),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.meal_record_search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                )
                IconButton(onClick = onAddRecipe) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.meal_record_add_recipe),
                    )
                }
            }

            if (query.isBlank() && candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.meal_record_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp),
                )
            } else if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_no_result_title, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.meal_record_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { it.id }) { item ->
                        RecordCandidateRow(item = item, onClick = { onPick(item) })
                    }
                }
            }
        }
    }
}

/** 记录模式里的一个候选：封面小图 + 菜名 + 分类。 */
@Composable
private fun RecordCandidateRow(item: RecipeListItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        MealThumb(item.coverPath)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.categories.isNotEmpty()) {
                Text(
                    text = item.categories.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 首页顶部的冲突提示。
 *
 * 不做「一键解决」按钮：哪一份是想要的只有人知道。这里只负责让人发现它存在，
 * 副本本身在列表里（名字带「（冲突副本）」），点进去比对、删掉不要的那份即可。
 */
@Composable
private fun ConflictBanner(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(R.string.conflict_banner_title, count),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.conflict_banner_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
