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
                            version = "v1.3.1 · 静默入账、轻弹窗与自动更新",
                            items = listOf(
                                "高置信度识别不再逐次询问：直接入账并弹 3 秒自动消失的底部轻卡片「记账成功」；只有识别把握低时才弹轻确认",
                                "修好「只识别到『支付』两个字的回归：节点遍历不再回收共享子树；自动识屏与摇一摇统一到同一套采集、去重和标签补齐逻辑，结果不再不一致",
                                "打开同一笔付款记录也能触发重复提醒：账单详情页现在会提交去重，确定重复弹「已记过」轻提示、疑似重复弹底部轻卡片，不再落通知栏",
                                "抖音订单页识别修复：支持只有「实付 ¥xx + 订单详情」没有「支付成功」的页面，并补齐金额/商户标签",
                                "无障碍守护：静默前台通知 + 每 60 秒检查开关；ADB / Shizuku 授予 WRITE_SECURE_SETTINGS 后自动恢复被系统撤销的授权，未授予时限频弹底部轻提示",
                                "关于页新增「检查 GitHub 更新」：发现新版本自动下载 APK 并拉起系统安装器（系统限制最后一步需点「安装」）"
                            )
                        )
                        ChangelogRow(
                            version = "v1.3.0 · 识屏准确度与自动化",
                            items = listOf(
                                "新增支付页面识别：能分辨「付款界面 / 收银台 / 支付成功页 / 账单详情」，用户打开付款界面时就能识别并记录（不再只有支付完成才响应）",
                                "修好微信全部付款界面识别失败：旧 1.5 秒去抖会吞掉真正的结果页事件；改为内容指纹 5 秒冷却，失败不再占窗口",
                                "微信空节点适配：结果页只返回空根节点时按 Activity 类名做 3 次有界重读，并补读活动窗口与多窗口",
                                "微信文案扩展与结构化：支持「已完成 / 微信支付 / 已存入对方零钱」等，并把金额、商户补成带标签的行交给抽取器",
                                "通知路补读 bigText / subText / infoText，微信把金额放在 bigText 时也能识别",
                                "重复确认与中低置信度不再拉起软件界面，改为屏幕底部悬浮卡片：仍然记账 / 修改 / 忽略；无悬浮窗权限时降级为点击才打开的普通通知",
                                "明确支付成功页给置信度小幅加成，让字段齐全的结果更容易自动入账；新增 13 项页面识别单测"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.3 · 侧载受限设置与最小权限",
                            items = listOf(
                                "依据第二轮真机日志纠正排查方向：6 次连接 / 4 次撤销期间 pid 始终不变、进程存活 3.4 分钟，说明后台保活、省电白名单对本案完全无效",
                                "修好侧载识别：com.android.packageinstaller 原本被当成正常来源，导致结论没提示「允许受限设置」；现在按 Android 13+ 受限设置与 Android 17 高级保护模式给出首要解法",
                                "新增 ADB 一次性解除受限设置：完整命令可直接在 App 内复制，仓库另附一键脚本，无需 root",
                                "无障碍服务声明最小权限化：去掉列出微信 / 支付宝 / 银行包名的 packageNames 和默认关闭的截屏能力，降低支付风控触发面；读节点主链路不受影响",
                                "诊断报告新增进程 pid 稳定性与侧载标记；自检页不再把「无截屏能力」误报成故障"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.2 · 无障碍诊断判因 + vivo 适配",
                            items = listOf(
                                "依据 vivo V2505A / Android 17 真机日志修正判断：服务连上 1 秒 / 27 秒就被关，是系统撤销授权，不是 App 崩溃或省电杀进程；弹窗和复制的 log 现在直接给出结论",
                                "修正误导性日志文案：解绑 / 销毁原本写成「进程退出」，实际是撤销授权后的正常生命周期；补上存活时长、pid、累计被撤销次数",
                                "连接事件也强制写入日志，复制回来的 log 不再只有一堆断开 ERROR，能还原完整时间线",
                                "修复 vivo / iQOO（OriginOS）看不到稳定运行设置卡片：原来只判断 OPPO 系，现在按厂商显示",
                                "补两条关键系统设置：Android 13+ 侧载 APK 需先「允许受限设置」；vivo 官方要求「后台高耗电 + 一键加速白名单」",
                                "诊断报告新增安装来源、Android 版本、服务存活时长和自动结论"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.1 · 无障碍保活 + 覆盖安装",
                            items = listOf(
                                "新增无障碍健康检查：服务被系统关闭或解绑时弹窗，可直接复制完整 log 发给开发者",
                                "新增 OrangeOS / ColorOS 稳定运行清单：锁定后台、允许自启动、电池白名单，以及用 Shevery / Shizuku 类工具锁定无障碍服务",
                                "固定调试签名：从这一版起，后续 CI 构建可以覆盖安装，不用每次先卸载旧版（首次从旧签名升级仍需卸载一次）",
                                "识屏服务被解绑 / 销毁时强制写一条诊断日志，不再因为没开日志开关而丢失现场"
                            )
                        )
                        ChangelogRow(
                            version = "v1.2.0 · 统计与记账闭环",
                            items = listOf(
                                "统计增强：日历按天看收支、近 12 个月消费热力图、同比 / 环比",
                                "年度预算：按年份独立存储，和月度预算互不覆盖",
                                "退款与报销：退款关联原账单并自动记回收入，报销到账自动入账",
                                "批量记账：一次录入多笔，统一分类 / 账户，批量改账户余额同事务处理",
                                "债务与分期：借入借出、每笔还款 / 收款、分期尾差自动补齐",
                                "信用卡账单日 / 还款日提醒，还款日前 3 天推一条"
                            )
                        )
                        ChangelogRow(
                            version = "v1.1.0 · 统计页统一 + 版本升级",
                            items = listOf(
                                "底部第 4 个 tab 改成「我的」：本月支出、收入、结余、累计笔数、记账天数、自动记账占比一屏看完",
                                "本月花在哪 Top3、预算进度直接摊在「我的」里，不用再翻统计页",
                                "设置入口收进右上角，设置页只留偏好项，改哪一行就点哪一行弹窗",
                                "新增存储排查：列出历史版本的数据库、备份、日志、缓存，自己勾选保留还是删除",
                                "记账数据 / 智能记账 / 数据安全三组入口集中到「我的」，去掉重复与内部编号",
                                "统计页整体换成全站风格：渐变净结余主卡、胶囊筛选、图表装进卡片、分类排行带占比条",
                                "版本号升到 1.1.0"
                            )
                        )
                        ChangelogRow(
                            version = "v1.0.0 · 预算 · 详情 · 摇一摇",
                            items = listOf(
                                "预算页重做：渐变主卡 + 总预算 + 分类预算进度条 + 日均可用",
                                "账单详情：编辑/复制/删除固定在底部，来源、原文等收进「更多信息」折叠",
                                "摇一摇换成熟识别算法：去重力 + 双向确认 + 冷却 + 处理中互斥，并支持灵敏度"
                            )
                        )
                        ChangelogRow(
                            version = "v1.0.0 · 识屏与识别",
                            items = listOf(
                                "识屏改走无障碍节点读屏，截图 OCR 只做兜底",
                                "识别成功率高直接记账；中等弹小卡片确认；失败才开表单",
                                "新增购买内容识别，备注写成「商户 · 商品」，认不准就当没有",
                                "同商户同金额 24 小时内重复时先问你，不自动重复记账",
                                "修复部分机型（Android 17）正则初始化崩溃导致自检闪退"
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
                text = "从 GitHub Releases 检查新版本；发现更新会自动下载 APK 并打开系统安装器。" +
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
                        else -> "检查 GitHub 更新"
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
