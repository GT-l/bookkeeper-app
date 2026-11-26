plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.azu.bookkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.azu.bookkeeper"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // 基础 / UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    // 桌面快捷记账小组件（Glance 声明式实现，类在 T7 落地）
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // 应用锁（BiometricPrompt 需 FragmentActivity 宿主）
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    // @HiltWorker 的注解处理器（KSP），与 androidx.hilt.work 同为 1.2.0
    ksp(libs.androidx.hilt.compiler)

    // 数据
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.kotlinx.serialization.json)

    // 异步 / 后台
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    // 文件 / 识别 / 图表 / 日志
    implementation(libs.kotlin.csv.jvm)
    // XLSX 读写需要完整 XSSF API；lite 版缺失 XSSFWorkbook/SXSSFWorkbook/OPCPackage 等类
    implementation(libs.poi.ooxml)
    implementation(libs.mlkit.text.recognition)
    // 中文 OCR 模型：拉丁模型识别不了中文支付页（识屏失败根因之一），见 OcrEngine
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mpandroidchart)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    // QA F-14：协程测试基建，供 Flow / runTest 用例使用（与 kotlinx-coroutines-android 同版本）
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
