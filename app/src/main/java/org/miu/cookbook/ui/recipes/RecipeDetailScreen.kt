package org.miu.cookbook.ui.recipes

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.miu.cookbook.R
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.components.EmptyState

/**
 * 食谱详情（M1 只读预览，M2 接上编辑）。
 *
 * 结构按需求排：最上面图片展示区（没图就整块不要），下面是文本区——
 * 菜名（一级标题）、分类 tag、原料、做法、备注。
 */
@Composable
fun RecipeDetailScreen(
    recipeId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val detailFlow = remember(recipeId) { repository.recipeDetail(recipeId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    val ui = remember(detail) { detail?.toDetailUi(container.filesDir) }

    Column(modifier = modifier.fillMaxSize()) {
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
        }

        val current = ui
        if (current == null) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.recipe_not_found),
                hint = stringResource(R.string.recipe_not_found_hint),
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
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

            // 菜名：一级标题
            Text(
                text = current.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (current.categories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CategoryTags(current.categories)
            }

            Section(stringResource(R.string.section_ingredients), current.ingredients.map { it.display })
            Section(stringResource(R.string.section_directions), current.directions, numbered = true)
            Section(stringResource(R.string.section_notes), current.notes, numbered = true)

            if (current.ingredients.isEmpty() && current.directions.isEmpty() && current.notes.isEmpty()) {
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

/** 二级标题 + 内容。原料是普通行，做法/备注是带编号的有序列表。 */
@Composable
private fun Section(title: String, plain: List<String>, numbered: Boolean = false) {
    if (plain.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    if (numbered) {
        plain.forEachIndexed { index, line ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(28.dp),
                )
                Text(text = line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        plain.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, lines: List<ContentLine>, numbered: Boolean) {
    if (lines.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    ContentLines(lines)
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
