# ===== SQLCipher =====
-keep class net.sqlcipher.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn net.sqlcipher.**

# ===== Kotlinx Serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.azu.bookkeeper.**$$serializer { *; }
-keepclassmembers class com.azu.bookkeeper.** {
    *** Companion;
}
-keepclasseswithmembers class com.azu.bookkeeper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== Apache POI (XLSX) =====
-dontwarn org.apache.poi.**
-dontwarn org.apache.commons.compress.**
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.schemas.** { *; }

# ===== ML Kit =====
-keep class com.google.mlkit.vision.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision.** { *; }
-dontwarn com.google.mlkit.vision.**

# ===== Room / Hilt / Coroutines =====
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn kotlinx.coroutines.**

# ===== T6 短信隐私：release 移除短信解析相关 verbose/debug 日志调用 =====
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
# 短信原文只在本地正则运算，任何情况下不得输出正文：保留类但不保留日志字符串拼接
-keep class com.azu.bookkeeper.automation.receiver.SmsCaptureParser { *; }
-keep class com.azu.bookkeeper.automation.receiver.SmsBroadcastReceiver { *; }

# ===== 系统绑定组件：类名/构造器绝不能被混淆或裁剪 =====
# 无障碍服务：system_server 通过清单里的类名跨进程绑定，混淆后类名变了就永远连不上
-keep class com.azu.bookkeeper.automation.service.PaymentAccessibilityService { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
# 通知监听服务同理
-keep class com.azu.bookkeeper.automation.service.PaymentNotificationListenerService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }
# 前台服务 / 广播 / 透明中转页（清单引用，防 R8 裁剪）
-keep class com.azu.bookkeeper.automation.service.AutoCaptureForegroundService { *; }
-keep class com.azu.bookkeeper.ui.quick.ShakeQuickAddService { *; }
-keep class com.azu.bookkeeper.ui.quick.QuickBallService { *; }
-keep class com.azu.bookkeeper.ui.quick.QuickBallToggleActivity { *; }
-keep class com.azu.bookkeeper.ui.quick.ScreenCaptureAuthActivity { *; }
-keep class com.azu.bookkeeper.automation.service.BootReceiver { *; }
-keep class com.azu.bookkeeper.automation.receiver.SmsBroadcastReceiver { *; }
-keep class com.azu.bookkeeper.ui.widget.QuickBookWidgetReceiver { *; }
