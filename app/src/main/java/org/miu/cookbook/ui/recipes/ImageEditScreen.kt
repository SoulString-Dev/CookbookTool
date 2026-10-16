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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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

    suspend fun reload() {
        images = repository.images(recipeId)
    }

    LaunchedEffect(recipeId) { reload() }

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
            repository.addImage(recipeId, imported)
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
                IconButton(onClick = onDone) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done), tint = Color.White)
                }
                Text(
                    text = stringResource(R.string.image_edit_title) + "  " +
                        stringResource(R.string.image_counter, pagerState.currentPage + 1, images.size),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
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
    val stridePx = with(LocalDensity.current) { (ThumbSize + ThumbGap).toPx() }

    Row(horizontalArrangement = Arrangement.spacedBy(ThumbGap)) {
        images.forEachIndexed { index, image ->
            val dragging = draggingIndex == index
            Box(
                modifier = Modifier
                    .size(ThumbSize)
                    .zIndex(if (dragging) 1f else 0f)
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
                                val target = (index + steps).coerceIn(0, images.lastIndex)
                                draggingIndex = null
                                dragOffset = 0f
                                if (target != index) onMove(index, target)
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
                    Text(
                        text = "★",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopEnd).padding(1.dp),
                    )
                }
            }
        }
    }
}
