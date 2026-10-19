package org.miu.cookbook.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.db.SyncStateEntity
import org.miu.cookbook.data.sync.SyncStatus
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.components.LocalMessenger
import org.miu.cookbook.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置页：外观 + 家庭共享两节。
 *
 * 顶栏由外面的 [org.miu.cookbook.ui.CookbookRoot] 提供（那里管着返回箭头与标题），
 * 这里**不要再套 Scaffold**——否则会出现两个标题、两个返回按钮。
 *
 * 两种形态共用一页：
 * - 没配对：填地址 / 配对码 / 设备名，点「配对」；
 * - 已配对：看状态、立即同步、断开。
 *
 * 配对码是**服务端**生成的一次性口令（写在服务端的 `pair-code.txt` 里），不是用户自己设的密码，
 * 所以这里不能给「记住我」之类的暗示——换个服务端就得重新配对。
 */
@Composable
fun SyncSettingsScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val container = LocalAppContainer.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()

    val syncState by container.repository.syncState.collectAsStateWithLifecycle(initialValue = null)
    val status by container.syncEngine.status.collectAsStateWithLifecycle()

    // 判断「已配对」看库里的地址：令牌在 SharedPreferences 里，但它变的时候这里不会重组
    val paired = syncState?.serverUrl?.isNotBlank() == true

    // 提示文案在组合里取好：launch 里不能调 stringResource
    val pairSuccess = stringResource(R.string.settings_pair_success)
    val pairFailed = stringResource(R.string.settings_pair_failed)
    val syncFailed = stringResource(R.string.settings_sync_failed_short)
    val disconnected = stringResource(R.string.settings_disconnected)
    val unknownReason = stringResource(R.string.settings_unknown_reason)
    val fallbackDeviceName = stringResource(R.string.settings_device_default)

    var primaryUrl by rememberSaveable { mutableStateOf(container.syncCredentials.primaryUrl) }
    var localUrl by rememberSaveable { mutableStateOf(container.syncCredentials.localUrl) }
    var pairCode by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceName(fallbackDeviceName)) }
    var showDisconnect by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 老版本只存了一个地址（在 sync_state 里）：升级上来时把它填进主地址，不然输入框是空的
    LaunchedEffect(syncState?.serverUrl) {
        val saved = syncState?.serverUrl.orEmpty()
        if (saved.isNotBlank() && primaryUrl.isBlank()) primaryUrl = saved
    }

    val primaryTrimmed = primaryUrl.trim()
    val primaryLooksOk = primaryTrimmed.startsWith("http://") || primaryTrimmed.startsWith("https://")
    val localTrimmed = localUrl.trim()
    val localLooksOk = localTrimmed.isEmpty() ||
        localTrimmed.startsWith("http://") || localTrimmed.startsWith("https://")
    val codeTrimmed = pairCode.trim()
    val codeLooksOk = codeTrimmed.length == 6 && codeTrimmed.all { it.isDigit() }
    val canPair = primaryLooksOk && localLooksOk && codeLooksOk && deviceName.isNotBlank() && !busy

    fun pair() {
        busy = true
        scope.launch {
            val result = container.syncEngine.pair(
                primaryUrl = primaryTrimmed,
                localUrl = localTrimmed,
                code = codeTrimmed,
                deviceName = deviceName.trim(),
            )
            busy = false
            result
                .onSuccess {
                    pairCode = ""
                    messenger.show(pairSuccess)
                    // 配对完立刻同步一次：不然用户还得自己点「立即同步」才知道通的
                    container.syncEngine.syncNow()
                }
                .onFailure { messenger.show(pairFailed + (it.message ?: unknownReason)) }
        }
    }

    fun syncNow() {
        scope.launch {
            container.syncEngine.syncNow().onFailure {
                messenger.show(syncFailed + (it.message ?: unknownReason))
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        SectionTitle(stringResource(R.string.theme_appearance))
        ThemeMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeModeChange(mode) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = mode == themeMode, onClick = { onThemeModeChange(mode) })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(mode.labelRes), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (!paired) {
            PairForm(
                primaryUrl = primaryUrl,
                onPrimaryUrlChange = { primaryUrl = it },
                primaryLooksOk = primaryLooksOk,
                localUrl = localUrl,
                onLocalUrlChange = { localUrl = it },
                localLooksOk = localLooksOk,
                pairCode = pairCode,
                onPairCodeChange = { input -> pairCode = input.filter { it.isDigit() }.take(6) },
                deviceName = deviceName,
                onDeviceNameChange = { deviceName = it },
                busy = busy,
                canPair = canPair,
                onPair = { pair() },
            )
        } else {
            PairedPanel(
                state = syncState,
                localUrl = container.syncCredentials.localUrl,
                status = status,
                onSync = { syncNow() },
                onDisconnect = { showDisconnect = true },
            )
        }
    }

    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text(stringResource(R.string.settings_disconnect_title)) },
            text = { Text(stringResource(R.string.settings_disconnect_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnect = false
                    scope.launch {
                        container.syncEngine.disconnect()
                        messenger.show(disconnected)
                    }
                }) {
                    Text(stringResource(R.string.settings_disconnect_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnect = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PairForm(
    primaryUrl: String,
    onPrimaryUrlChange: (String) -> Unit,
    primaryLooksOk: Boolean,
    localUrl: String,
    onLocalUrlChange: (String) -> Unit,
    localLooksOk: Boolean,
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    busy: Boolean,
    canPair: Boolean,
    onPair: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_sync_section))
    Text(
        text = stringResource(R.string.settings_sync_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = primaryUrl,
        onValueChange = onPrimaryUrlChange,
        singleLine = true,
        isError = primaryUrl.isNotBlank() && !primaryLooksOk,
        label = { Text(stringResource(R.string.settings_primary_label)) },
        placeholder = { Text(stringResource(R.string.settings_primary_hint)) },
        supportingText = {
            Text(
                stringResource(
                    if (primaryUrl.isNotBlank() && !primaryLooksOk) {
                        R.string.settings_server_invalid
                    } else {
                        R.string.settings_primary_help
                    },
                ),
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = localUrl,
        onValueChange = onLocalUrlChange,
        singleLine = true,
        isError = localUrl.isNotBlank() && !localLooksOk,
        label = { Text(stringResource(R.string.settings_local_label)) },
        placeholder = { Text(stringResource(R.string.settings_local_hint)) },
        supportingText = {
            Text(
                stringResource(
                    if (localUrl.isNotBlank() && !localLooksOk) {
                        R.string.settings_server_invalid
                    } else {
                        R.string.settings_local_help
                    },
                ),
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = pairCode,
        onValueChange = onPairCodeChange,
        singleLine = true,
        label = { Text(stringResource(R.string.settings_pair_code_label)) },
        placeholder = { Text(stringResource(R.string.settings_pair_code_hint)) },
        supportingText = { Text(stringResource(R.string.settings_pair_code_where)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = deviceName,
        onValueChange = onDeviceNameChange,
        singleLine = true,
        label = { Text(stringResource(R.string.settings_device_name_label)) },
        supportingText = { Text(stringResource(R.string.settings_device_name_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onPair,
        enabled = canPair,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(stringResource(if (busy) R.string.settings_pairing else R.string.settings_pair_action))
    }
}

@Composable
private fun PairedPanel(
    state: SyncStateEntity?,
    localUrl: String,
    status: SyncStatus,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val running = status is SyncStatus.Running

    SectionTitle(stringResource(R.string.settings_sync_section))
    Text(
        text = stringResource(R.string.settings_paired_title),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_server_value, state?.serverUrl.orEmpty()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (localUrl.isNotBlank()) {
        Text(
            text = stringResource(R.string.settings_local_value, localUrl),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val lastSyncAt = state?.lastSyncAt ?: 0L
    Text(
        text = stringResource(
            R.string.settings_last_sync,
            if (lastSyncAt > 0L) formatTime(lastSyncAt) else stringResource(R.string.settings_never_synced),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // 上次失败的下一句放这里：设置页是唯一会说清「为什么没同步上」的地方
    val lastError = state?.lastError.orEmpty()
    if (lastError.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_last_error, lastError),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(12.dp))
    when (status) {
        is SyncStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_syncing),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is SyncStatus.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    R.string.settings_sync_done,
                    status.report.pulled,
                    status.report.pushed,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.report.conflicts > 0) {
                Text(
                    text = stringResource(R.string.settings_sync_conflicts, status.report.conflicts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is SyncStatus.Failed -> Text(
            text = stringResource(R.string.settings_sync_failed, status.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        SyncStatus.Idle -> Unit
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onSync,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (running) R.string.settings_syncing else R.string.settings_sync_now))
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onDisconnect,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_disconnect))
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.settings_sync_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

/** 设备名默认取手机型号，两台一模一样的手机也确实没法自动区分，让用户自己改。 */
private fun defaultDeviceName(fallback: String): String {
    val model = Build.MODEL.orEmpty().trim()
    return model.ifBlank { fallback }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMAT)
}
