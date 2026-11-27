package com.azu.bookkeeper.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
                    onCheck = updateViewModel::checkForUpdate
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
                            version = "v1.3.2 · 弹窗重做与重复修复",
                            items = listOf(
                                "修复：金额无效或商户名泛化时误判重复，每笔都被当成重复订单",
                                "修复：同一笔旧账单只提醒一次，不再反复弹重复提示",
                                "修复：余额 / 优惠等非实付金额不再被当成支付金额",
                                "重做：识屏弹窗改为与软件一致的草莓粉卡片，轻提示与确认卡片统一风格",
                                "更名：检查 GitHub 更新改为检查更新",
                                "优化：通知栏只保留一条自动记账常驻通知"
                            )
                        )
                        ChangelogRow(
                            version = "v1.3.1 · 静默入账与轻弹窗",
                            items = listOf(
                                "新增：高置信度直接记账，只弹 3 秒轻提示",
                                "修复：只识别到「支付」的回归，摇一摇与自动识屏结果统一",
                                "修复：账单详情页可触发重复检测，重复改底部轻卡片，不再进通知栏",
                                "新增：抖音订单页识别、无障碍守护、关于页检查更新"
                            )
                        )
                        ChangelogRow(
                            version = "v1.3.0 · 识屏识别",
                            items = listOf(
                                "新增：付款界面 / 支付成功页 / 账单详情识别",
                                "修复：微信支付页事件被去抖吞掉，空根节点按类名重读",
                                "新增：微信文案扩展、通知多字段补齐、底部轻确认卡片"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.3 · 无障碍授权",
                            items = listOf(
                                "修复：侧载应用受限设置判因",
                                "新增：ADB 一键解锁、无障碍最小权限声明"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.2 · 诊断增强",
                            items = listOf(
                                "修复：无障碍判因与 vivo 引导可见性",
                                "新增：完整日志时间线与自动结论"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.1 · 保活与签名",
                            items = listOf(
                                "新增：无障碍健康检查与一键复制 log",
                                "新增：固定调试签名，覆盖安装不再需要先卸载"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.0 · 统计与记账闭环",
                            items = listOf(
                                "新增：日历 / 热力图 / 同比环比、年度预算",
                                "新增：退款、报销、批量记账、债务分期、信用卡提醒"
                            )
                        )
                        ChangelogRow(
                            version = "v1.1.0 · 我的页与存储排查",
                            items = listOf(
                                "新增：「我的」tab、存储排查、统计页卡片化",
                                "修复：设置页瘦身、重复入口合并"
                            )
                        )
                        ChangelogRow(
                            version = "v1.0.0 · 首个版本",
                            items = listOf(
                                "新增：手动记账、预算、账单详情、摇一摇识屏",
                                "修复：Android 17 正则初始化崩溃"
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

/** GitHub 更新：检查 → 下载 → 自动打开系统安装器（最后一步系统强制要求用户点「安装」）。 */
@Composable
private fun UpdateCard(state: AboutViewModel.UiState, onCheck: () -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "检查更新",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "检查是否有新版本；发现更新会自动下载 APK 并打开系统安装器。" +
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
                    text = "下载进度 ${state.progress}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
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
