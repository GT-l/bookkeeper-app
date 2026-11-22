package com.azu.bookkeeper

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.azu.bookkeeper.automation.verify.VerificationDialogHost
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.ui.navigation.AppNavHost
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import com.azu.bookkeeper.ui.theme.BookkeeperTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 唯一 Activity，全部页面由 Navigation Compose 承载；继承 FragmentActivity 以承载 BiometricPrompt。
 *
 * T7：四个快捷入口（Shortcut / Widget / 悬浮球 / FGS 通知）与 T5 校对通知的 Intent
 * 统一由 [QuickCaptureLauncher.resolve] 归一化，冷启动作为 startDestination，
 * 热启动（singleTop → onNewIntent）通过 SharedFlow 触发 NavHost 导航，两条路径都落到 BillEdit(quickPrefill)。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val quickCaptureEvents = MutableSharedFlow<QuickCaptureRequest>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startDestination = QuickCaptureLauncher.resolve(intent)
            ?.let { Screen.billEdit(prefill = it.toPrefill()) }
            ?: Screen.Home.route
        setContent {
            BookkeeperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController, startDestination = startDestination)

                    LaunchedEffect(Unit) {
                        quickCaptureEvents.collect { request ->
                            navController.navigate(Screen.billEdit(prefill = request.toPrefill())) {
                                launchSingleTop = true
                            }
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
        }
    }
}
