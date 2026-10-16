package org.miu.cookbook.ui.recipes.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.miu.cookbook.R
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.di.LocalAppContainer
import kotlinx.coroutines.delay
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.ContentOutline
import org.miu.cookbook.ui.recipes.MdBodyStyle
import org.miu.cookbook.ui.recipes.Mdh3Style
import org.miu.cookbook.ui.recipes.contentLines

/** 输入框和序号共用的上边距，两边一致序号才不会飘。 */
private val LineTopPadding = 10.dp

/** 编辑中的一行：内容和层级都放在这里，保存时再变成 [ContentLine]。 */
private data class LineDraft(
    val value: TextFieldValue,
    val level: Int,
    val isHeading: Boolean,
) {
    val text: String get() = value.text

    fun asLine() = ContentLine(text = text, level = level, isHeading = isHeading)
}

private fun blankDraft(level: Int = 0, isHeading: Boolean = false) =
    LineDraft(value = TextFieldValue(""), level = level, isHeading = isHeading)

/**
 * 做法 / 备注的行编辑器。
 *
 * 敲键规则（都在 [onValueChange] 和退格那里）：
 * - 行首敲一次空格 → 缩进一级（最多两级），再敲再缩；
 * - 行首敲句号（`.` 或 `。`）→ 变成三级标题，再敲一下退回列表项；
 * - 行首退格 → 标题变回列表项 / 子级退回上一级 / 已经顶级就把这一行并掉；
 * - 回车换行时继承当前行的层级。
 */
@Composable
fun ContentEditScreen(
    recipeId: Long,
    section: ContentSection,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val detailFlow = remember(recipeId) { repository.recipeDetail(recipeId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    val drafts = remember { mutableStateListOf<LineDraft>() }
    val requesters = remember { mutableStateListOf<FocusRequester>() }
    var loaded by remember { mutableStateOf(false) }
    var pendingFocus by remember { mutableStateOf<Int?>(null) }
    // 一边编一边落盘：停手 400ms 写一次，退出页面时把最后没来得及写的那一下补上
    val scheduleSave = rememberDebouncedSaver<List<ContentLine>> { snapshot ->
        repository.setContent(recipeId, section, snapshot)
    }

    LaunchedEffect(detail) {
        val loadedDetail = detail
        if (!loaded && loadedDetail != null) {
            loadedDetail.contentLines(section).forEach { line ->
                drafts.add(
                    LineDraft(
                        value = TextFieldValue(line.text, TextRange(line.text.length)),
                        level = line.level,
                        isHeading = line.isHeading,
                    ),
                )
            }
            if (drafts.isEmpty()) drafts.add(blankDraft())
            repeat(drafts.size) { requesters.add(FocusRequester()) }
            loaded = true
        }
    }

    LaunchedEffect(pendingFocus) {
        val target = pendingFocus ?: return@LaunchedEffect
        // 等这一帧的新行挂上 requesters，再去要焦点
        withFrameNanos { }
        requesters.getOrNull(target)?.let { runCatching { it.requestFocus() } }
        pendingFocus = null
    }

    fun insertBelow(index: Int) {
        // 换行继承层级，但标题下面新起的是列表项
        drafts.add(index + 1, blankDraft(level = drafts[index].level))
        requesters.add(index + 1, FocusRequester())
        pendingFocus = index + 1
        scheduleSave(drafts.map { it.asLine() })
    }

    fun deleteLine(index: Int) {
        if (drafts.size <= 1) {
            drafts[0] = blankDraft()
            scheduleSave(drafts.map { it.asLine() })
            return
        }
        if (index == 0) {
            // 第一行没有「上一行」可并，只能整行删掉
            drafts.removeAt(0)
            requesters.removeAt(0)
            pendingFocus = 0
            scheduleSave(drafts.map { it.asLine() })
            return
        }
        // 并进上一行，文本不丢，光标停在接缝处
        val removed = drafts[index]
        val previous = drafts[index - 1]
        val caret = previous.value.text.length
        drafts[index - 1] = previous.copy(
            value = TextFieldValue(previous.value.text + removed.value.text, TextRange(caret)),
        )
        drafts.removeAt(index)
        requesters.removeAt(index)
        pendingFocus = index - 1
        scheduleSave(drafts.map { it.asLine() })
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 输入法一弹出来就让出下半屏，正在编的那几行不会被键帽压住
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.content_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            val numbers = remember(drafts.toList()) { ContentOutline.numbers(drafts.map { it.asLine() }) }

            drafts.forEachIndexed { index, draft ->
                val current = drafts[index]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Spacer(Modifier.width((current.level * 20).dp))

                    if (!current.isHeading) {
                        Text(
                            text = "${numbers.getOrElse(index) { 0 }}.",
                            // 和输入框用同一档字号、同一个上边距，序号才会跟第一行文字对齐
                            style = MdBodyStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = LineTopPadding)
                                .width(28.dp),
                        )
                    }

                    BasicTextField(
                    value = draft.value,
                    onValueChange = { new ->
                        val old = drafts[index].value
                        // 只在「行首刚敲下一个字」时做特殊处理
                        val typed = if (new.text.length == old.text.length + 1 && new.selection.start == 1) {
                            new.text[0]
                        } else {
                            null
                        }
                        val updated = when (typed) {
                            // 行首空格 = 往下一层；句号 = 这一行变三级标题（顺手清掉行首空格）
                            ' ' -> current.let { line ->
                                line.copy(level = ContentOutline.indent(line.asLine()).level)
                            }

                            '.', '。' -> current.let { line ->
                                val toggled = ContentOutline.toggleHeading(line.asLine())
                                line.copy(
                                    value = line.value.copy(
                                        text = toggled.text,
                                        selection = TextRange(toggled.text.length),
                                    ),
                                    level = toggled.level,
                                    isHeading = toggled.isHeading,
                                )
                            }

                            else -> current.copy(value = new)
                        }
                        drafts[index] = updated
                        scheduleSave(drafts.map { it.asLine() })
                    },
                    // 必须带颜色：BasicTextField 拿到没写颜色的样式会退回黑色，夜间模式就看不见了
                    textStyle = if (draft.isHeading) {
                        Mdh3Style().copy(color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        MdBodyStyle()
                    },
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { insertBelow(index) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requesters.getOrElse(index) { FocusRequester() })
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            when {
                                event.key == Key.Enter -> {
                                    insertBelow(index)
                                    true
                                }

                                event.key == Key.Backspace &&
                                    draft.value.selection.start == 0 &&
                                    draft.value.selection.end == 0 -> {
                                    when {
                                        draft.isHeading -> {
                                            drafts[index] = draft.copy(isHeading = false)
                                            scheduleSave(drafts.map { it.asLine() })
                                        }

                                        draft.level > 0 -> {
                                            drafts[index] = draft.copy(
                                                level = ContentOutline.outdent(draft.asLine()).level,
                                            )
                                            scheduleSave(drafts.map { it.asLine() })
                                        }

                                        else -> deleteLine(index)
                                    }
                                    true
                                }

                                else -> false
                            }
                        }
                        .padding(top = LineTopPadding, bottom = 10.dp),
                )
                }
            }

            Spacer(Modifier.width(1.dp))
        }
    }
}
