package com.azu.bookkeeper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.service.AutoCaptureForegroundService
import com.azu.bookkeeper.automation.verify.VerificationDialogHost
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.core.SafeMode
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.bill.ObserveBillsUseCase
import com.azu.bookkeeper.ui.navigation.AppEntryRouter
import com.azu.bookkeeper.ui.navigation.AppNavHost
import com.azu.bookkeeper.ui.debug.CaptureLogActivity
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.quick.QuickBallService
import com.azu.bookkeeper.ui.quick.ShakeQuickAddService
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import com.azu.bookkeeper.ui.theme.BookkeeperTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 唯一 Activity，全部页面由 Navigation Compose 承载；继承 FragmentActivity 以承载 BiometricPrompt。
 *
 * T7：四个快捷入口（Shortcut / Widget / 悬浮球 / FGS 通知）与 T5 校对通知的 Intent
 * 统一由 [QuickCaptureLauncher.resolve] 归一化，冷启动作为 startDestination，
 * 热启动（singleTop → onNewIntent）通过 SharedFlow 触发 NavHost 导航，两条路径都落到 BillEdit(quickPrefill)。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var automationSettings: AutomationSettingsDataStore
    @Inject lateinit var permissionGuide: PermissionGuide
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var observeBills: ObserveBillsUseCase

    /**
     * replay=1 很关键：热启动时 onNewIntent 可能早于 Compose 的 LaunchedEffect 订阅，
     * 普通 SharedFlow 会把这次事件丢掉，表现为「点通知/摇一摇后 App 起来了但停在首页」。
     */
    private val quickCaptureEvents = MutableSharedFlow<QuickCaptureRequest>(
        replay = 1, extraBufferCapacity = 4
    )

    /** 普通页面落地（如常驻通知点击 → 自动化设置页）；热启动时转发给 NavHost。 */
    private val destinationEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    /** 悬浮球兜底恢复用；随 Activity 销毁取消，避免泄漏。 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.azu.bookkeeper.core.BootTrace.step("APP", "MainActivity.onCreate", "startDest 解析前")
        val startDestination = QuickCaptureLauncher.resolve(intent)
            ?.let { Screen.billEdit(prefill = it.toPrefill()) }
            ?: AppEntryRouter.resolve(intent)
            ?: Screen.Home.route
        observeTodayExpenseForNotification()
        setContent {
            BookkeeperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController, startDestination = startDestination)

                    // 启动即检查：上次是否闪退过。崩过就把栈直接摆到用户眼前并支持一键复制。
                    // 为什么放这里而不是自检页：自检页本身可能就是崩的那一环，藏在里面等于没写。
                    var crashReport by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        crashReport = runCatching {
                            com.azu.bookkeeper.core.CrashReportGate.pending(this@MainActivity)
                        }.getOrNull()
                    }
                    crashReport?.let { report ->
                        AlertDialog(
                            onDismissRequest = {
                                runCatching {
                                    com.azu.bookkeeper.core.CrashReportGate.markShown(this@MainActivity)
                                }
                                crashReport = null
                            },
                            title = { Text("上次闪退了，已抓到崩溃栈") },
                            text = {
                                Text(
                                    report.take(1200),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    runCatching {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                            as? ClipboardManager
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText("崩溃栈", report)
                                        )
                                        com.azu.bookkeeper.core.CrashReportGate.markShown(this@MainActivity)
                                    }
                                    crashReport = null
                                    Toast.makeText(
                                        this@MainActivity, "崩溃栈已复制，粘贴发我即可", Toast.LENGTH_LONG
                                    ).show()
                                    runCatching {
                                        startActivity(
                                            Intent(this@MainActivity, CaptureLogActivity::class.java)
                                        )
                                    }
                                }) { Text("复制并看详情") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    runCatching {
                                        com.azu.bookkeeper.core.CrashReportGate.markShown(this@MainActivity)
                                    }
                                    crashReport = null
                                }) { Text("知道了") }
                            }
                        )
                    }

                    LaunchedEffect(Unit) {
                        quickCaptureEvents.collect { request ->
                            navController.navigate(Screen.billEdit(prefill = request.toPrefill())) {
                                launchSingleTop = true
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        destinationEvents.collect { route ->
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    }

                    // 前台低置信度校对弹窗（悬浮窗/通知由 VerificationController 降级处理）
                    VerificationDialogHost()
                }
            }
        }
    }

    /** singleTop 热启动：新 Intent 不重建 Activity，必须在此转发给 NavHost。 */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        QuickCaptureLauncher.resolve(newIntent)?.let { request ->
            quickCaptureEvents.tryEmit(request)
            return
        }
        // 常驻通知（→ 自动化设置页）等普通页面入口：走独立事件流，避免和记账表单混淆。
        AppEntryRouter.resolve(newIntent)?.let { route -> destinationEvents.tryEmit(route) }
    }

    /**
     * 回到前台时兜底恢复：
     * 1) 悬浮球：开关已开 + 已授权 + 球没在跑 → 补拉起；
     * 2) 常驻通知：自动化开关已开时恢复 / 刷新前台服务（见 [AutoCaptureForegroundService.ensureRunning]）。
     *    Android 13+ 在未授权 POST_NOTIFICATIONS 期间投递的前台通知会被系统直接丢弃且不补发，
     *    用户事后去系统设置授权也不会自动出现 → 必须在这里再 startForeground 一次刷出来，
     *    否则就表现为「明明开了通知权限，通知栏却一直没有常驻通知」。
     * 覆盖「进程被系统杀掉后重新打开 App」和「刚从一个页面切回来」两种场景，
     * 是 START_NOT_STICKY（避免崩溃循环）下的主要恢复手段。
     */
    override fun onResume() {
        super.onResume()
        uiScope.launch {
            // 安全模式：上次启动崩溃过就不再自动拉起悬浮层服务，
            // 否则「拉起→崩溃→重启→再拉起」会形成永远打不开的死循环。
            val safeMode = runCatching { SafeMode.isActive(this@MainActivity) }.getOrDefault(false)
            if (!safeMode) {
                runCatching {
                    val enabled = automationSettings.floatingBallEnabled.first()
                    if (enabled && !QuickBallService.isRunning && permissionGuide.canDrawOverlays()) {
                        QuickBallService.start(this@MainActivity)
                    }
                }
            }
            runCatching {
                AutoCaptureForegroundService.ensureRunning(this@MainActivity, automationSettings)
            }
            // 摇一摇走独立前台服务：与悬浮层安全模式无关，回到前台时按设置恢复
            runCatching {
                if (automationSettings.quickGesture.first() == QuickGestureCodes.SHAKE) {
                    ShakeQuickAddService.start(this@MainActivity)
                }
            }
        }
    }

    /**
     * 常驻通知上的「今日支出」跟随账单实时刷新。
     *
     * 为什么需要它：用户从常驻通知点「记一笔」→ 表单保存 → popBackStack 回到首页，
     * 整个过程 Activity 没有 pause/resume，onCreate 只跑一次，通知上的金额会一直是旧值。
     * 这里订阅当前账本的账单流（Room 一写库就推新列表），算出当日支出再回写通知。
     *
     * 只更新「已经存在的常驻通知」，不拉起服务、不新建通知，异常全部吞掉记日志，
     * 记账功能绝不因为通知刷新失败受影响。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTodayExpenseForNotification() {
        uiScope.launch {
            runCatching {
                settings.defaultBookId
                    .flatMapLatest { bookId ->
                        if (bookId <= 0L) flowOf(emptyList()) else observeBills(bookId)
                    }
                    .catch { e ->
                        Timber.w(e, "observe today expense failed")
                        emit(emptyList())
                    }
                    .collect {
                        // 账单有任何变动就让服务重新取数并刷新通知（含今日/本月/预算等用户勾选项）
                        AutoCaptureForegroundService.refreshNow(this@MainActivity)
                    }
            }.onFailure { Timber.w(it, "today expense collector stopped") }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
