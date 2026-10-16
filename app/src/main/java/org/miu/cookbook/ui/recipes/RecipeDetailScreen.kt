package org.miu.cookbook.ui.recipes

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
    onBack: () -> Unit,
    onEditIngredients: () -> Unit,
    onEditContent: (ContentSection) -> Unit,
    modifier: Modifier = Modifier,
    /** 长按菜单选「编辑食谱」进来时直接就是编辑态。 */
    startEditing: Boolean = false,
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
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        val current = ui

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }

            Spacer(Modifier.weight(1f))

            if (current != null) {
                if (editing) {
                    TextButton(onClick = { editing = false }) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (current.imagePaths.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.imagePaths) { path ->
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
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

            // 菜名：一级标题。编辑态只让改名字，井号是灰的、改不了。
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    OutlinedTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.recipe_title_hint)) },
                        textStyle = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (editing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "##",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.recipe_edit_categories),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
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

    val current = ui
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
                    style = MaterialTheme.typography.bodyLarge,
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
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "##",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
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
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(28.dp),
                )
                Text(text = line.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
