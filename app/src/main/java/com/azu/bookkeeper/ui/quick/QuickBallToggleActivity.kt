package com.azu.bookkeeper.ui.quick

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.azu.bookkeeper.core.SafeMode
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 通知栏「显示 / 隐藏悬浮球」按钮的落地页。
 *
 * 用一个透明 Activity 而不是 BroadcastReceiver：Android 8+ 禁止后台应用 startService，
 * 从通知点进 Activity 属于用户交互，此刻可以合法地启动 / 停止服务；做完立刻 finish。
 */
class QuickBallToggleActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            runCatching {
                val settings = EntryPointAccessors.fromApplication(
                    applicationContext, BallToggleEntryPoint::class.java
                ).automationSettings()
                val wasOn = settings.floatingBallEnabled.first()
                settings.setFloatingBallEnabled(!wasOn)
                if (wasOn) {
                    QuickBallService.stop(applicationContext)
                    Toast.makeText(applicationContext, "悬浮球已隐藏", Toast.LENGTH_SHORT).show()
                } else {
                    SafeMode.clear(applicationContext)
                    QuickBallService.start(applicationContext)
                    Toast.makeText(applicationContext, "悬浮球已显示", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(applicationContext, "操作失败，请到 设置→自动化 重试", Toast.LENGTH_SHORT)
                    .show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BallToggleEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
}
