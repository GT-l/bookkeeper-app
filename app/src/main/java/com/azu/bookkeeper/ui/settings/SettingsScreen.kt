package com.azu.bookkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.BuildConfig
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.navigation.Screen

/**
 * 设置页（从「我的」右上角进入）。
 *
 * 上一版是一屏 OutlinedTextField + 一堆按钮，滚动很长，还混着「统计（T4）」这种开发期占位入口。
 * 这一版的原则：
 * - **只放偏好**：默认账本/账户、显示格式、应用锁、自动备份、存储排查、关于；
 *   功能入口（分类/标签/导入导出/自动化/识屏自检）全部搬到「我的」，两页不再重复；
 * - **读为主、改才弹窗**：平时只看到「当前是什么」，点一行才弹出编辑，页面高度直接砍一半；
 * - 去掉所有 `（T4）`/`（T5）` 之类的内部编号和重复入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var dialog by remember { mutableStateOf<SettingDialog?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "设置",
                subtitle = "偏好与数据，只影响这台设备"
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingGroup(title = "记账偏好") {
                    SettingRow(
                        emoji = "📚",
                        title = "默认账本",
                        value = state.books.firstOrNull { it.bookId == state.defaultBookId }?.name ?: "未设置",
                        onClick = { dialog = SettingDialog.Book }
                    )
                    GroupDivider()
                    SettingRow(
                        emoji = "💳",
                        title = "默认账户",
                        value = state.accounts.firstOrNull { it.accountId == state.defaultAccountId }?.name
                            ?: "未设置",
                        onClick = { dialog = SettingDialog.Account }
                    )
                    GroupDivider()
                    SettingRow(
                        emoji = "💱",
                        title = "货币符号",
                        value = state.currency.ifBlank { "CNY" },
                        hint = "只影响显示",
                        onClick = { dialog = SettingDialog.Currency }
                    )
                    GroupDivider()
                    SettingRow(
                        emoji = "🕒",
                        title = "日期格式",
                        value = state.dateFormat,
                        onClick = { dialog = SettingDialog.DateFormat }
                    )
                    GroupDivider()
                    SettingRow(
                        emoji = "📅",
                        title = "每周从哪天开始",
                        value = weekLabel(state.weekStartDay),
                        onClick = { dialog = SettingDialog.WeekStart }
                    )
                }
            }

            item {
                SettingGroup(title = "安全") {
                    SettingSwitchRow(
                        emoji = "🔐",
                        title = "应用锁",
                        subtitle = "退出 5 分钟后自动上锁",
                        checked = state.appLockEnabled,
                        onCheckedChange = { viewModel.setAppLock(it) }
                    )
                    if (state.appLockEnabled) {
                        GroupDivider()
                        SettingRow(
                            emoji = "🔢",
                            title = "数字密码",
                            value = if (state.appLockPin.isBlank()) "未设置" else "已设置（••••）",
                            onClick = { dialog = SettingDialog.Pin }
                        )
                    }
                }
            }

            item {
                SettingGroup(title = "数据") {
                    SettingSwitchRow(
                        emoji = "💾",
                        title = "每日自动本地备份",
                        subtitle = "凌晨自动打包一次，只存在本机",
                        checked = state.autoBackupEnabled,
                        onCheckedChange = { viewModel.setAutoBackup(it) }
                    )
                    GroupDivider()
                    SettingRow(
                        emoji = "🧹",
                        title = "存储排查",
                        value = "检查历史遗留数据",
                        onClick = { navController.navigate(Screen.StorageAudit.route) }
                    )
                    GroupDivider()
                    SettingRow(
                        emoji = "☁️",
                        title = "备份与恢复",
                        value = "手动备份 / 还原",
                        onClick = { navController.navigate(Screen.Backup.route) }
                    )
                }
            }

            item {
                SettingGroup(title = "关于") {
                    SettingRow(
                        emoji = "✨",
                        title = "关于与更新介绍",
                        value = "v${BuildConfig.VERSION_NAME}",
                        onClick = { navController.navigate(Screen.About.route) }
                    )
                }
            }

            state.message?.let { message ->
                item {
                    SoftCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.clearMessage() }
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            item {
                Text(
                    text = "少花点儿 v${BuildConfig.VERSION_NAME} · 全部数据保存在本机，不上传任何服务器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    // ---------------- 弹窗 ----------------

    when (dialog) {
        SettingDialog.Book -> ChoiceDialog(
            title = "默认账本",
            options = state.books.map { it.bookId to it.name },
            selectedId = state.defaultBookId,
            onSelect = { id ->
                viewModel.setDefaultBook(id)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SettingDialog.Account -> ChoiceDialog(
            title = "默认账户",
            options = state.accounts.map { it.accountId to it.name },
            selectedId = state.defaultAccountId,
            onSelect = { id ->
                viewModel.setDefaultAccount(id)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SettingDialog.WeekStart -> ChoiceDialog(
            title = "每周从哪天开始",
            options = listOf(1L to "周一", 7L to "周日"),
            selectedId = state.weekStartDay.toLong(),
            onSelect = { id ->
                viewModel.setWeekStart(id.toInt())
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SettingDialog.Currency -> TextInputDialog(
            title = "货币符号",
            label = "符号或代码",
            placeholder = "CNY / ¥ / $",
            initial = state.currency,
            keyboardType = KeyboardType.Text,
            onConfirm = { value ->
                viewModel.setCurrency(value)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SettingDialog.DateFormat -> TextInputDialog(
            title = "日期格式",
            label = "Java 时间格式",
            placeholder = "yyyy-MM-dd HH:mm",
            initial = state.dateFormat,
            presets = listOf("yyyy-MM-dd HH:mm", "MM-dd HH:mm", "yyyy/MM/dd"),
            onConfirm = { value ->
                viewModel.setDateFormat(value)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        SettingDialog.Pin -> TextInputDialog(
            title = if (state.appLockPin.isBlank()) "设置应用锁密码" else "修改应用锁密码",
            label = "至少 4 位数字",
            placeholder = "例如 1234",
            initial = "",
            keyboardType = KeyboardType.NumberPassword,
            onConfirm = { value ->
                viewModel.setPinDraft(value)
                viewModel.savePin()
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        null -> Unit
    }
}

/** 设置页内的弹窗种类。 */
private enum class SettingDialog { Book, Account, Currency, DateFormat, WeekStart, Pin }

private fun weekLabel(day: Int): String = if (day == 7) "周日" else "周一"

/** 分组：小标题 + 白卡容器。 */
@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        SoftCard(modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

/** 只读一行：emoji + 标题 + 当前值 + 箭头。点开弹窗改。 */
@Composable
private fun SettingRow(
    emoji: String,
    title: String,
    value: String,
    hint: String? = null,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 15.sp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingSwitchRow(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 15.sp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 58.dp, end = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    )
}

/** 单选弹窗：账本 / 账户 / 周起始日共用。 */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<Long, String>>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) {
                Text("还没有可选项，先去「我的」里创建", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    options.forEach { (id, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(id) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (id == selectedId) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (id == selectedId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (id == selectedId) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                        GroupDivider()
                    }
                }
            }
        },
        confirmButton = { CuteOutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 文本输入弹窗：货币符号 / 日期格式 / 密码共用，[presets] 非空时给几个快捷选项。 */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    placeholder: String,
    initial: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    presets: List<String> = emptyList(),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column {
                CuteTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = label,
                    placeholder = placeholder,
                    keyboardType = keyboardType,
                    modifier = Modifier.fillMaxWidth()
                )
                if (presets.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { preset ->
                            CuteOutlinedButton(onClick = { text = preset }) {
                                Text(preset, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { CutePrimaryButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { CuteOutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}
