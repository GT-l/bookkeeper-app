package com.azu.bookkeeper.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azu.bookkeeper.BuildConfig
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.SoftCard

/**
 * 关于与更新介绍。
 *
 * 之前「关于」只是一个跳转项，用户看不到版本和更新内容；反馈问题时还得自己找版本号。
 * 这里把版本、最近几版改了什么、数据存哪、权限用来干嘛一次讲清，并给一个「复制版本信息」按钮，
 * 反馈时直接粘贴即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val updateViewModel: AboutViewModel = hiltViewModel()
    val updateState by updateViewModel.ui.collectAsState()
    val pickApkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) updateViewModel.installLocalApk(uri) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "关于", subtitle = "少花点儿 · 本地记账") }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Header() }

            item {
                UpdateCard(
                    state = updateState,
                    onCheck = updateViewModel::checkForUpdate,
                    onOpenBrowser = updateViewModel::openInBrowser,
                    onPickLocal = { pickApkLauncher.launch("application/vnd.android.package-archive") }
                )
            }

            item {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "更新介绍",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        ChangelogRow(
                            version = "v1.3.16 · 最新",
                            items = listOf(
                                "识屏：截到空白帧不再当成「没找到金额」，会明确报出原因并改用录屏通道",
                                "OCR 引擎异常单独报错，不再和「页面没有文字」混成一个结果",
                                "下载：断点续传 + 实时速率与剩余时间 + 直链优先（v1.3.15）"
                            )
                        )
                        ChangelogRow(
                            version = "v1.3 系列 · 识屏自动化",
                            items = listOf(
                                "红包收发、转账进出、收款、云闪付全场景识别，方向与备注自动区分",
                                "识屏不可用时通知栏提醒，一键补记或去开启",
                                "检查更新、安装包自动清理、弹窗与轻提示统一样式"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2 系列 · 统计与稳定",
                            items = listOf(
                                "日历 / 热力图 / 同比环比、年度预算、退款与批量记账",
                                "无障碍诊断与保活引导、固定签名（覆盖安装不用先卸载）"
                            )
                        )
                        ChangelogRow(
                            version = "v1.0 - v1.1 · 基础记账",
                            items = listOf(
                                "手动记账、预算、账单详情、摇一摇识屏；账本与账户管理"
                            ),
                            last = true
                        )
                    }
                }
            }

            item {
                InfoCard(
                    title = "数据存在哪",
                    lines = listOf(
                        "账本、账单、账户全部存在本机的加密数据库里（SQLCipher，密钥由系统 Keystore 保管）。",
                        "没有服务器、没有账号体系，也不会上传任何账单内容。",
                        "卸载 App 会连数据一起删除，重要数据请先在「备份与恢复」里导出。"
                    )
                )
            }

            item {
                InfoCard(
                    title = "权限都用来干什么",
                    lines = listOf(
                        "无障碍：读取支付页面的文字来识别金额与商户；只有你主动摇一摇或开启自动记账时才工作。",
                        "通知使用权 / 短信：识别支付通知与银行短信作为辅助来源，可随时在系统设置里关闭。",
                        "悬浮窗：只在开启悬浮球时使用。",
                        "存储排查只读取 App 自己的私有目录，不碰相册、下载和其它应用。"
                    )
                )
            }

            item {
                CuteOutlinedButton(
                    onClick = {
                        val info = buildString {
                            append("少花点儿 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                            append("包名 ${BuildConfig.APPLICATION_ID}\n")
                            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                            append("机型 ${Build.MANUFACTURER} ${Build.MODEL}")
                        }
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("bookkeeper-version", info))
                            Toast.makeText(context, "版本信息已复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制版本信息（反馈用）") }
            }

            item {
                Text(
                    text = "少花点儿 · 一个只给自己用的记账 App",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** 检查 → 下载 → 打开系统安装器；网络不通时给浏览器下载 / 本地安装两条兜底路。 */
@Composable
private fun UpdateCard(
    state: AboutViewModel.UiState,
    onCheck: () -> Unit,
    onOpenBrowser: () -> Unit,
    onPickLocal: () -> Unit
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "检查更新",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "检查是否有新版本；发现更新会自动下载 APK 并打开系统安装器。" +
                    "下载失败时可改用浏览器下载，或选本地 APK 安装。" +
                    "Android 不允许无声安装，最后一步需要你点系统页面的「安装」（同签名可直接覆盖，账单不丢）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Text(
                    // v1.3.15：除了百分比，还给出已下/总量、实时速率和剩余时间——
                    // 88MB 的包在 1% = 0.9MB 时只看百分比会误以为「卡住」。
                    text = buildString {
                        append("下载进度 ${state.progress}%")
                        if (state.progressDetail.isNotBlank()) {
                            append(" · ")
                            append(state.progressDetail)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (state.messageIsError) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    CuteOutlinedButton(
                        onClick = onOpenBrowser,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    ) { Text("用浏览器下载") }
                    CuteOutlinedButton(
                        onClick = onPickLocal,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    ) { Text("选本地 APK") }
                }
            }
            CuteOutlinedButton(
                onClick = onCheck,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                enabled = !state.checking && !state.downloading
            ) {
                Text(
                    when {
                        state.checking -> "正在检查…"
                        state.downloading -> "正在下载…"
                        else -> "检查更新"
                    }
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                )
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🐷", fontSize = 28.sp)
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = "少花点儿",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · 本地记账，不上传",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun ChangelogRow(version: String, items: List<String>, last: Boolean = false) {
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = version,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Column(modifier = Modifier.padding(start = 15.dp, top = 4.dp)) {
            items.forEach { line ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            lines.forEach { line ->
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}
