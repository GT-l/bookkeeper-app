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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "更新介绍",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
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
