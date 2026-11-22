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
