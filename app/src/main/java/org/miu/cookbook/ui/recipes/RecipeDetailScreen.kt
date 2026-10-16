package org.miu.cookbook.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.domain.markdown.MarkdownRecipe
import org.miu.cookbook.domain.markdown.RecipeMarkdown
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.ui.components.EmptyState
import org.miu.cookbook.ui.history.RecordMealDialog
import org.miu.cookbook.ui.history.RecipeMealHistoryDialog

/**
 * 食谱详情：不编辑时是纯预览，点右上角铅笔进编辑态。
 *
 * 结构按需求排：最上面图片展示区（没图就整块不要），下面是文本区——
 * 菜名（一级标题）、分类 tag、原料、做法、备注。
 * 编辑态下菜名、分类、原料、做法、备注分别点开编辑；菜名的「一级标题」是序列化时
 * 自动加的 `# `，界面上只显示一个灰色的井号，改不了，也就锁住了。
 */
@Composable
fun RecipeDetailScreen(
    recipeId: Long,
    onEditIngredients: () -> Unit,
    onEditContent: (ContentSection) -> Unit,
    onEditImages: () -> Unit,
    modifier: Modifier = Modifier,
    /** 长按菜单选「编辑食谱」进来时直接就是编辑态。 */
    startEditing: Boolean = false,
    /** 把菜名报给常驻的顶栏，让它显示当前是哪道菜。 */
    onTitleChange: (String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val detailFlow = remember(recipeId) { repository.recipeDetail(recipeId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    val ui = remember(detail) { detail?.toDetailUi(container.filesDir) }

    var editing by remember { mutableStateOf(startEditing) }
    var titleDraft by remember { mutableStateOf("") }
    var showMarkdown by remember { mutableStateOf(false) }
    var categoryPickerOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // 非编辑态：右下角加号直接记这道菜；菜名右边的按钮看这道菜的饮食历史
    var recordOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 返回键：编辑态先退出编辑，别一按就把这一页关掉
    BackHandler(enabled = editing) {
        editing = false
        titleDraft = ui?.title.orEmpty()
    }

    Box(modifier = modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        val current = ui

        // 菜名和返回箭头都在常驻的顶栏上，这一行只放页面自己的操作
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.weight(1f))

            if (current != null) {
                if (editing) {
                    TextButton(onClick = {
                        editing = false
                        titleDraft = current.title
                    }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.setTitle(recipeId, titleDraft)
                                editing = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_done))
                    }
                } else {
                    TextButton(onClick = { showMarkdown = true }) {
                        Text(stringResource(R.string.recipe_view_markdown))
                    }
                    IconButton(onClick = { editing = true }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.recipe_edit_title),
                        )
                    }
                }
            }
        }

        if (current == null) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.recipe_not_found),
                hint = stringResource(R.string.recipe_not_found_hint),
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        LaunchedEffect(current.id) {
            titleDraft = current.title
        }

        // 顶栏跟着菜名变（编辑态打字时也实时跟着）
        LaunchedEffect(current.title) { onTitleChange(current.title) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (editing) {
                // 编辑态：点这一块进图片编辑（跟点正文进文本编辑是一个套路）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onEditImages)
                        .padding(12.dp),
                ) {
                    if (current.imagePaths.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        current.imagePaths.take(3).forEach { path ->
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            ) {
                                AsyncImage(
                                    model = path,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.image_edit),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(16.dp))
            } else if (current.imagePaths.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(current.imagePaths) { index, path ->
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { viewerIndex = index }
                                .semantics {
                                    contentDescription = "$index"
                                },
                        ) {
                            AsyncImage(
                                model = path,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // 菜名：一级标题。编辑态只让改名字；一级标题本来就固定，界面上不画井号。
            if (editing) {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.recipe_title_hint)) },
                    textStyle = Mdh1Style(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current.title,
                        style = Mdh1Style(),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = { historyOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.meal_history_view))
                    }
                }
            }

            if (editing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.recipe_edit_categories),
                        style = Mdh2Style(),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { categoryPickerOpen = true }) {
                        Text(stringResource(R.string.recipe_section_edit))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (current.categories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CategoryTags(current.categories)
            } else if (editing) {
                Text(
                    text = stringResource(R.string.recipe_section_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IngredientSection(
                title = stringResource(R.string.section_ingredients),
                plain = current.ingredients.map { it.display },
                alwaysShow = editing,
                onEdit = if (editing) onEditIngredients else null,
            )
            Section(
                title = stringResource(R.string.section_directions),
                lines = current.directions,
                alwaysShow = editing,
                onEdit = if (editing) ({ onEditContent(ContentSection.DIRECTIONS) }) else null,
            )
            Section(
                title = stringResource(R.string.section_notes),
                lines = current.notes,
                alwaysShow = editing,
                onEdit = if (editing) ({ onEditContent(ContentSection.NOTE) }) else null,
            )

            if (!editing &&
                current.ingredients.isEmpty() &&
                current.directions.isEmpty() &&
                current.notes.isEmpty()
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.recipe_detail_placeholder_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.recipe_detail_placeholder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(48.dp))
        }
      }

      // 非编辑态才有加号：点一下直接记这道菜（编辑态隐藏，跟需求一致）
      if (!editing && ui != null) {
        FloatingActionButton(
            onClick = { recordOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.meal_record_title),
            )
        }
      }
    }

    val current = ui
    if (recordOpen && current != null) {
        RecordMealDialog(
            recipeTitle = current.title,
            onDismiss = { recordOpen = false },
            onConfirm = { epochDay, slot ->
                recordOpen = false
                scope.launch { repository.logMeal(recipeId, epochDay, slot) }
            },
        )
    }

    if (historyOpen && current != null) {
        RecipeMealHistoryDialog(
            recipeId = recipeId,
            recipeTitle = current.title,
            repository = repository,
            onDismiss = { historyOpen = false },
        )
    }

    if (categoryPickerOpen) {
        CategoryPickerDialog(
            recipeIds = listOf(recipeId),
            repository = repository,
            onDismiss = { categoryPickerOpen = false },
            onApplied = { categoryPickerOpen = false },
        )
    }

    if (showMarkdown && current != null) {
        MarkdownDialog(
            markdown = RecipeMarkdown.serialize(
                MarkdownRecipe(
                    title = current.title,
                    categories = current.categories,
                    ingredients = current.ingredients,
                    directions = current.directions,
                    notes = current.notes,
                ),
            ),
            onDismiss = { showMarkdown = false },
        )
    }
}

@Composable
private fun MarkdownDialog(markdown: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recipe_markdown_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.recipe_markdown_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = markdown,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(markdown))
                    copied = true
                },
            ) {
                Text(
                    if (copied) {
                        stringResource(R.string.action_done)
                    } else {
                        stringResource(R.string.action_copy)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryTags(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            SuggestionChip(onClick = {}, label = { Text(name) })
        }
    }
}

/** 二级标题 + 编辑入口。原料是普通行，做法/备注是带编号的有序列表。 */
@Composable
private fun IngredientSection(
    title: String,
    plain: List<String>,
    alwaysShow: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    if (plain.isEmpty() && !alwaysShow) return
    SectionHeader(title, onEdit)
    if (plain.isEmpty()) {
        EmptySectionHint()
    } else {
        Column(modifier = onEdit?.let { Modifier.clickable(onClick = it) } ?: Modifier) {
            plain.forEach { line ->
                Text(
                    text = line,
                    style = MdBodyStyle(),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    lines: List<ContentLine>,
    alwaysShow: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    if (lines.isEmpty() && !alwaysShow) return
    SectionHeader(title, onEdit)
    if (lines.isEmpty()) {
        EmptySectionHint()
    } else {
        Column(modifier = onEdit?.let { Modifier.clickable(onClick = it) } ?: Modifier) {
            ContentLines(lines)
        }
    }
}

@Composable
private fun SectionHeader(title: String, onEdit: (() -> Unit)?) {
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = Mdh2Style())
        if (onEdit != null) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.recipe_section_edit))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EmptySectionHint() {
    Text(
        text = stringResource(R.string.recipe_section_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 有序列表：每层自己编号，子层缩进。 */
@Composable
private fun ContentLines(lines: List<ContentLine>) {
    val counters = IntArray(3)
    lines.forEach { line ->
        val level = line.level.coerceIn(0, 2)
        if (line.isHeading) {
            Text(
                text = line.text,
                style = if (level == 0) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    start = (level * 20).dp,
                    top = 8.dp,
                    bottom = 2.dp,
                ),
            )
        } else {
            counters[level]++
            for (i in level + 1..2) counters[i] = 0
            Row(
                modifier = Modifier.padding(
                    start = (level * 20).dp,
                    top = 2.dp,
                    bottom = 2.dp,
                ),
            ) {
                Text(
                    text = "${counters[level]}.",
                    style = MdBodyStyle(),
                    modifier = Modifier.width(28.dp),
                )
                Text(text = line.text, style = MdBodyStyle())
            }
        }
    }
}
