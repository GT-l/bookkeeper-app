package com.azu.bookkeeper.core

import android.os.Build

/**
 * ROM 识别与专项兼容（OrangeOS / ColorOS / OriginOS 特调）。
 *
 * 「OrangeOS」在用户反馈里通常指 vivo OriginOS（俗称橘子系统）或 OPPO ColorOS 分支，
 * 两家都有「无障碍开关被系统自动关掉」的已知行为，但解法不同：
 * vivo 官方要求把应用加入「后台高耗电」与「一键加速白名单」，OPPO 系则看自启动 / 允许后台运行。
 * 因此保活引导不能只判断 OPPO 系——v1.2.2 修复：vivo 机型必须一并命中，否则用户根本看不到引导卡片。
 *
 * OPPO 系 ROM 的已知限制：
 * 1) 权限弹窗关闭瞬间系统会把应用短暂判为后台 → startForegroundService 可能抛
 *    ForegroundServiceStartNotAllowedException（调用方需带一次延迟重试）；
 * 2) 后台服务默认被「自启动管理」拦 → 必须引导用户开自启动 + 允许后台运行；
 * 3) 无障碍服务在崩溃后会被系统自动取消 → 服务端已全链路防崩（第十轮）。
 */
object RomCompat {

    /** OPPO 系 ROM（含 OrangeOS / ColorOS / 一加氢 OS / realme）。 */
    fun isOppoFamily(): Boolean = runCatching {
        val manufacturer = Build.MANUFACTURER?.uppercase().orEmpty()
        val brand = Build.BRAND?.uppercase().orEmpty()
        manufacturer.contains("OPPO") || manufacturer.contains("ONEPLUS") ||
            manufacturer.contains("REALME") || brand.contains("ONEPLUS") ||
            brand.contains("REALME")
    }.getOrDefault(false)

    /** vivo / iQOO（OriginOS）。vivo 官方对「无障碍自动关闭」的解法是后台高耗电 + 一键加速白名单。 */
    fun isVivoFamily(): Boolean = runCatching {
        val manufacturer = Build.MANUFACTURER?.uppercase().orEmpty()
        val brand = Build.BRAND?.uppercase().orEmpty()
        manufacturer.contains("VIVO") || manufacturer.contains("IQOO") ||
            brand.contains("VIVO") || brand.contains("IQOO")
    }.getOrDefault(false)

    /** 更精确的 OrangeOS 判断：系统版本串里带 Orange 标识。 */
    fun isOrangeOs(): Boolean = runCatching {
        (Build.DISPLAY?.uppercase().orEmpty().contains("ORANGE")) ||
            (Build.VERSION.INCREMENTAL?.uppercase().orEmpty().contains("ORANGE"))
    }.getOrDefault(false)

    /** 是否命中「国产定制 ROM 保活专项引导」：OPPO 系、OrangeOS 分支、vivo / iQOO 全覆盖。 */
    fun needsKeepAliveGuidance(): Boolean =
        isOppoFamily() || isOrangeOs() || isVivoFamily()

    /** 兼容旧调用点的别名，语义与 [needsKeepAliveGuidance] 一致。 */
    fun needsOppoGuidance(): Boolean = needsKeepAliveGuidance()

    /** 引导卡片标题：别再把 vivo 用户写成 OPPO。 */
    fun keepAliveLabel(): String = when {
        isVivoFamily() -> "OriginOS / OrangeOS 稳定运行设置"
        isOrangeOs() -> "OrangeOS 稳定运行设置"
        else -> "OrangeOS / ColorOS 稳定运行设置"
    }

    /**
     * ROM 是否具备「灵动岛」类焦点展示（小米焦点通知 / 华为·荣耀实况窗 / OPPO 系流体云）。
     * 这类 ROM 会把 App 的 ongoing 进度通知自动聚合成屏幕顶部胶囊；无公开第三方 SDK，
     * 应用侧只需投递常驻进度通知即可命中该形态（见 CaptureProgressNotifier）。
     */
    fun isFocusIslandCapable(): Boolean = runCatching {
        val manufacturer = Build.MANUFACTURER?.uppercase().orEmpty()
        val brand = Build.BRAND?.uppercase().orEmpty()
        val device = Build.DEVICE?.uppercase().orEmpty()
        manufacturer.contains("XIAOMI") || manufacturer.contains("HUAWEI") ||
            manufacturer.contains("HONOR") || isOppoFamily() ||
            brand.contains("XIAOMI") || brand.contains("HUAWEI") ||
            brand.contains("HONOR") || device.contains("MI ")
    }.getOrDefault(false)

    /** ROM 展示名（诊断用）。 */
    fun displayName(): String = runCatching {
        "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.VERSION.RELEASE} (${Build.DISPLAY})"
    }.getOrDefault("unknown")
}
