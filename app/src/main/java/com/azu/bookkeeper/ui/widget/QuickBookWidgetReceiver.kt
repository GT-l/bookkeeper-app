package com.azu.bookkeeper.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 桌面组件 Receiver。类名与 AndroidManifest 已声明的
 * `.ui.widget.QuickBookWidgetReceiver` 严格一致（T1 预留声明，T7 落地实现）。
 * Glance 首次绑定会自动调用 [QuickCaptureWidget.provideGlance]，无需覆写 onUpdate。
 */
class QuickBookWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()
}
