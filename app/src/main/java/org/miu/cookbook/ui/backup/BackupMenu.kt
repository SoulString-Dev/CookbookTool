package org.miu.cookbook.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.di.LocalAppContainer

/**
 * 顶栏右侧的「⋮」：多选 / 导出 / 导入。
 *
 * 侧边栏里只有「食谱」「饮食历史」两个主界面（需求就是这么定的），所以这些入口
 * 放在顶栏，不往侧边栏里塞。多选以前是浮动搜索栏上的一个编辑图标，现在收进这里，
 * 两个主界面共用一套语义。
 *
 * 导出走 `CreateDocument`（用户挑存哪），导入走 `OpenDocument`——两边都用 SAF，
 * 不需要任何存储权限。导入是**整库替换**，所以先弹窗把「会覆盖掉什么」说清楚。
 */
@Composable
fun BackupMenu(
    repository: CookbookRepository,
    onMessage: (String) -> Unit,
    onStartSelection: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 整库导入导出可能要几十秒：挂在页面自己的 scope 上，用户一返回就被取消，
    // 会停在「表清空了一半、图还没换过去」。所以挂在应用级的 appScope 上，跑完为止。
    val appScope = LocalAppContainer.current.appScope

    var menuOpen by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf(0 to 0) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        appScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { repository.exportBackup(it) }
                    ?: error(context.getString(R.string.backup_no_stream))
            }.onSuccess {
                onMessage(context.getString(R.string.backup_exported))
            }.onFailure {
                onMessage(context.getString(R.string.backup_failed, it.message.orEmpty()))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImport = uri
            confirmImport = true
        }
    }

    LaunchedEffect(confirmImport) {
        if (confirmImport) counts = repository.counts()
    }

    Box(modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.backup_menu),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onStartSelection != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_start_selection)) },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onStartSelection()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.backup_export)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    exportLauncher.launch("cookbook-backup-${LocalDate.now()}.zip")
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.backup_import)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    importLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream"),
                    )
                },
            )
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = {
                confirmImport = false
                pendingImport = null
            },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_import_message,
                        counts.first,
                        counts.second,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingImport
                    confirmImport = false
                    pendingImport = null
                    if (uri == null) return@TextButton
                    appScope.launch {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use {
                                repository.importBackup(it)
                            } ?: error(context.getString(R.string.backup_no_stream))
                        }.onSuccess {
                            onMessage(context.getString(R.string.backup_imported))
                        }.onFailure {
                            onMessage(context.getString(R.string.backup_failed, it.message.orEmpty()))
                        }
                    }
                }) {
                    Text(stringResource(R.string.backup_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmImport = false
                    pendingImport = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
