package com.azu.bookkeeper.ui.quick

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.azu.bookkeeper.automation.service.AutoCaptureForegroundService
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * 透明的截屏授权中转页。
 *
 * 为什么需要它：MediaProjection 的系统授权对话框只能由 Activity 拉起，
 * 摇一摇触发点在前台服务里，无法直接弹。缺授权时拉起本页 → 弹系统授权 →
 * 拿到结果后建立会话 → 通知摇一摇服务继续识屏（ACTION_CAPTURE_NOW）。
 *
 * 页面本身无 UI（Translucent 主题），授权完自动关闭，用户只会看到系统对话框。
 */
class ScreenCaptureAuthActivity : ComponentActivity() {

    private val screenCapture: ScreenCaptureManager by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            CaptureAuthEntryPoint::class.java
        ).screenCapture()
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val ok = runCatching { screenCapture.startSession(result.resultCode, data) {} }
                .getOrDefault(false)
            if (ok) {
                Toast.makeText(this, "已授权，继续识屏…", Toast.LENGTH_SHORT).show()
                ShakeQuickAddService.captureNow(applicationContext)
            } else {
                Toast.makeText(this, "授权失败：系统未返回可用的截屏会话", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "已取消截屏授权，可在自动化设置里重新授权", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (screenCapture.hasActiveSession()) {
            ShakeQuickAddService.captureNow(applicationContext)
            finish()
            return
        }
        // Compose 需要 setContent，否则部分 ROM 上透明 Activity 会被判为异常窗口
        setContent { }
        // 提前拉起 mediaProjection 类型的前台服务：Android 14+ 要求
        // getMediaProjection / createVirtualDisplay 之前必须有它在跑
        AutoCaptureForegroundService.startForProjection(applicationContext)
        runCatching { launcher.launch(screenCapture.createCaptureIntent()) }
            .onFailure {
                Timber.w(it, "launch capture consent failed")
                Toast.makeText(this, "无法打开截屏授权：${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                finish()
            }
    }
}

/** 授权页只需要 ScreenCaptureManager 一个依赖。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureAuthEntryPoint {
    fun screenCapture(): ScreenCaptureManager
}
