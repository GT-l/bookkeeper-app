package com.azu.bookkeeper.automation.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.azu.bookkeeper.automation.permission.AccessibilityHealth
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.verify.OverlayVerifyView
import com.azu.bookkeeper.core.CaptureLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 无障碍存活检查（v1.3.2 起**不再自己常驻通知**）。
 *
 * v1.3.1 的守护服务独立前台通知，加上自动记账与摇一摇，通知栏常驻三条。
 * 现在检查逻辑由「自动记账」前台服务每 15 秒调用 [checkNow]，
 * 全 App 只保留一条常驻通知。
 *
 * 检查内容：系统开关被撤销 → 有 WRITE_SECURE_SETTINGS 就写回并读回验证；
 * 写回后仍被撤销累计 3 次 → 弹底部轻提示引导关闭高级保护 / 用 Shizuku 锁定。
 */
class A11yWatchdogService : android.app.Service() {

    override fun onBind(intent: Intent?) = null

    /** 已由 AutoCaptureForegroundService 托管，不再独立启动为前台服务。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        android.app.Service.START_NOT_STICKY

    companion object {
        private const val PREFS = "a11y_watchdog"
        private const val KEY_LAST_PROMPT = "last_prompt_at"
        private const val KEY_RESTORE_FAILS = "restore_fails"
        private const val PROMPT_INTERVAL_MS = 10 * 60_000L

        /** 由自动记账前台服务定时调用；整段 runCatching，绝不影响宿主服务。 */
        fun checkNow(context: Context) {
            runCatching {
            val app = context.applicationContext
            val entry = runCatching {
                EntryPointAccessors.fromApplication(app, WatchdogEntryPoint::class.java)
            }.getOrNull() ?: return
            val guide = runCatching { entry.permissionGuide() }.getOrNull() ?: return
            if (guide.isAccessibilityEnabled()) {
                // v1.3.8：识屏恢复 → 撤掉「识屏没在工作」提醒，不留残影
                CaptureHealthNotifier.clear(app)
                return
            }
            if (!guide.accessibilityServiceEverConnected()) {
                // 自动化开着但从没连上过：不是「被系统关掉」，而是根本没授权
                CaptureHealthNotifier.notifyUnavailable(
                    app, "无障碍识屏还没开启，付款后不会自动记账"
                )
                return
            }

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastPrompt = prefs.getLong(KEY_LAST_PROMPT, 0L)

            if (AccessibilityHealth.tryRestore(app)) {
                // 写回后读回验证：vivo 会写回后再次撤销，不验证就误以为恢复成功
                val restored = runCatching { guide.isAccessibilityEnabled() }.getOrDefault(false)
                val fails = prefs.getInt(KEY_RESTORE_FAILS, 0)
                if (restored) {
                    prefs.edit().putInt(KEY_RESTORE_FAILS, 0).apply()
                    CaptureLog.forceInfo(app, "A11Y", "守护检查：无障碍授权恢复成功")
                } else {
                    val next = fails + 1
                    prefs.edit().putInt(KEY_RESTORE_FAILS, next).apply()
                    CaptureLog.force(
                        app, "A11Y",
                        "守护写回授权后系统再次撤销（第 $next 次）：需关闭高级保护模式 / " +
                            "检查受限设置 / 用 Shizuku 或 Shevery 锁定服务"
                    )
                    if (next >= 3 && now - lastPrompt >= PROMPT_INTERVAL_MS) {
                        prefs.edit().putLong(KEY_LAST_PROMPT, now).apply()
                        showPrompt(
                            app, entry.overlay(),
                            "系统反复撤销无障碍授权",
                            "自动恢复已尝试 $next 次仍被撤销。请到 设置 → 安全 关闭「高级保护模式」，" +
                                "确认「允许受限设置」已开；仍不行需要用 Shizuku / Shevery 锁定本服务。"
                        )
                    }
                    // v1.3.8：写回失败同样要落到通知栏——用户不进 App 也能看到识屏坏了
                    CaptureHealthNotifier.notifyUnavailable(
                        app,
                        "无障碍识屏被系统反复关闭（已尝试自动恢复 $next 次），付款后不会自动记账"
                    )
                }
                return
            }

            // v1.3.8：识屏确实不可用 → 先保证通知栏有一条（内部 30 分钟节流），
            // 再考虑轻卡片的 10 分钟节流。轻卡片只在 App 前台可见，而「识屏坏了」
            // 往往是用户在其他 App 里付完款才发现的。
            CaptureHealthNotifier.notifyUnavailable(
                app, "无障碍识屏被系统关闭了，付款后不会自动记账"
            )
            if (now - lastPrompt < PROMPT_INTERVAL_MS) return
            prefs.edit().putLong(KEY_LAST_PROMPT, now).apply()
            showPrompt(
                app, entry.overlay(),
                "无障碍服务被系统关闭了",
                "自动记账已暂停。点「去开启」回到系统无障碍页重新打开；" +
                    "若反复被关，跑一次 scripts/android_unlock_accessibility.sh restore 让守护自动恢复。"
            )
            }
        }

        private fun showPrompt(
            app: Context,
            overlay: OverlayVerifyView,
            title: String,
            detail: String
        ) {
            val shown = runCatching {
                overlay.showConfirm(
                    title = title,
                    detail = detail,
                    confirmLabel = "去开启",
                    editLabel = null,
                    onConfirm = {
                        runCatching {
                            app.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onDiscard = {},
                    autoDismissMs = 12_000L
                )
            }.getOrDefault(false)
            CaptureLog.forceInfo(app, "A11Y", "守护检测到无障碍开关被关闭，轻提示已显示=$shown")
        }
    }
}

/** 惰性依赖：不用 @AndroidEntryPoint 字段注入，避免宿主服务启动期依赖异常。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WatchdogEntryPoint {
    fun permissionGuide(): PermissionGuide
    fun overlay(): OverlayVerifyView
}
