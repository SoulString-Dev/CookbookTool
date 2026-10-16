package org.miu.cookbook.ui.recipes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.db.RecipeImageEntity
import org.miu.cookbook.data.image.ImageStore
import org.miu.cookbook.di.LocalAppContainer

private val ThumbSize = 56.dp
private val ThumbGap = 8.dp

/**
 * 图片编辑：黑底大图 + 底部缩略图条。
 *
 * 所有改动**立即写库**（删、换序、设封面），没有「完成」才保存这一说——
 * 和正文编辑不同，图片本身就存在磁盘上，草稿反而要处理「拍完照又取消」这种边界。
 */
@Composable
fun ImageEditScreen(recipeId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()

    var images by remember { mutableStateOf<List<RecipeImageEntity>>(emptyList()) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RecipeImageEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val maxImages = MAX_IMAGES
    val pagerState = rememberPagerState { images.size.coerceAtLeast(1) }
    val failedMessage = stringResource(R.string.image_import_failed)
    val fullMessage = stringResource(R.string.image_full)

    suspend fun reload() {
        images = repository.images(recipeId)
    }

    LaunchedEffect(recipeId) { reload() }

    // 上次拍照留下的临时文件（等回调的时候离开了页面，launcher 跟着注销，就没机会删）
    DisposableEffect(Unit) {
        File(context.cacheDir, "camera").listFiles()?.forEach { it.delete() }
        onDispose { pendingCameraFile?.delete() }
    }

    // 删掉最后一张之后，页码要收回到有效范围
    LaunchedEffect(images.size) {
        if (images.isNotEmpty() && pagerState.currentPage > images.lastIndex) {
            pagerState.scrollToPage(images.lastIndex)
        }
    }

    fun importImage(imported: org.miu.cookbook.data.image.ImportedImage?) {
        if (imported == null) {
            message = failedMessage
            return
        }
        scope.launch {
            if (repository.addImage(recipeId, imported) == null) {
                // 到上限了：文件已经落盘，得自己收
                ImageStore.deleteFiles(container.filesDir, imported.path, imported.thumbPath)
                message = fullMessage
            }
            reload()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { importImage(ImageStore.importFromUri(context, uri, container.filesDir)) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved: Boolean ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (!saved || file == null) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val imported = ImageStore.importFromFile(file, container.filesDir)
            file.delete()
            importImage(imported)
        }
    }

    fun takePhoto() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        cameraLauncher.launch(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题和返回在常驻顶栏上，这一行只放这一页自己的操作
                Text(
                    text = stringResource(R.string.image_counter, pagerState.currentPage + 1, images.size),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val image = images.getOrNull(pagerState.currentPage) ?: return@IconButton
                        scope.launch {
                            repository.setCover(recipeId, image.id)
                            reload()
                        }
                    },
                    enabled = images.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.image_set_cover),
                        tint = if (images.getOrNull(pagerState.currentPage)?.isCover == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White
                        },
                    )
                }
                IconButton(
                    onClick = { deleteTarget = images.getOrNull(pagerState.currentPage) },
                    enabled = images.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.image_delete),
                        tint = Color.White,
                    )
                }
            }

            if (images.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.image_empty_hint),
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    AsyncImage(
                        model = File(container.filesDir, images.getOrNull(page)?.path ?: ""),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Text(
                text = stringResource(R.string.image_drag_hint),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThumbnailStrip(
                    images = images,
                    currentIndex = pagerState.currentPage,
                    baseDir = container.filesDir,
                    onSelect = { index -> scope.launch { pagerState.scrollToPage(index) } },
                    onMove = { from, to ->
                        scope.launch {
                            repository.moveImage(recipeId, from, to)
                            reload()
                        }
                    },
                )
                if (images.size < maxImages) {
                    Box {
                        IconButton(onClick = { addMenuOpen = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.image_add), tint = Color.White)
                        }
                        DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.image_take_photo)) },
                                onClick = {
                                    addMenuOpen = false
                                    takePhoto()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.image_pick_gallery)) },
                                onClick = {
                                    addMenuOpen = false
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.image_delete_title)) },
            text = { Text(stringResource(R.string.image_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            repository.deleteImage(target)
                            reload()
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.image_edit_title)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/**
 * 缩略图条。按住一张往左右拖，松手按位移算出落点。
 *
 * 拖动中不改数据、只改视觉位移，这样 [pointerInput] 的 key 不会变，手势不会被重组打断。
 * 为了让人看清会落到哪，拖动时把被拖那张抬起来（放大 + 阴影），其余缩略图让开位置，
 * 空出来的槽上盖一个半透明的提示块。
 */
@Composable
private fun ThumbnailStrip(
    images: List<RecipeImageEntity>,
    currentIndex: Int,
    baseDir: File,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val stridePx = with(density) { (ThumbSize + ThumbGap).toPx() }
    val liftPx = with(density) { 10.dp.toPx() }

    // 松手会落到哪一格：拖动中实时算，用来让位和画提示
    val from = draggingIndex
    val target = from?.let { (it + (dragOffset / stridePx).roundToInt()).coerceIn(0, images.lastIndex) }

    /** 松手后每一张占哪个槽（被拖的那张自己跟手走，不算在这里）。 */
    fun slotOf(index: Int): Int = when {
        from == null || target == null -> index
        index == from -> target
        from < target && index in (from + 1)..target -> index - 1
        target < from && index in target until from -> index + 1
        else -> index
    }

    val stripWidth = ThumbSize * images.size + ThumbGap * (images.size - 1).coerceAtLeast(0)

    Box(Modifier.width(stripWidth).height(ThumbSize)) {
        if (from != null && target != null && target != from) {
            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = target * stridePx }
                    .size(ThumbSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            )
        }

        images.forEachIndexed { index, image ->
            val dragging = index == from
            val x = if (dragging) index * stridePx + dragOffset else slotOf(index) * stridePx
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = x
                        translationY = if (dragging) -liftPx else 0f
                        val scale = if (dragging) 1.15f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(ThumbSize)
                    .zIndex(if (dragging) 2f else 1f)
                    .shadow(if (dragging) 12.dp else 0.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (index == currentIndex) MaterialTheme.colorScheme.primary else Color.DarkGray)
                    .padding(2.dp)
                    .pointerInput(index, images.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.x
                            },
                            onDragEnd = {
                                val steps = (dragOffset / stridePx).roundToInt()
                                val landing = (index + steps).coerceIn(0, images.lastIndex)
                                draggingIndex = null
                                dragOffset = 0f
                                if (landing != index) onMove(index, landing)
                            },
                            onDragCancel = {
                                draggingIndex = null
                                dragOffset = 0f
                            },
                        )
                    }
                    .clickable { onSelect(index) },
            ) {
                AsyncImage(
                    model = File(baseDir, image.thumbPath.ifBlank { image.path }),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                )
                if (image.isCover) {
                    val coverLabel = stringResource(R.string.image_cover_badge)
                    Text(
                        text = "★",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(1.dp)
                            .semantics { contentDescription = coverLabel },
                    )
                }
            }
        }
    }
}
