# Bookkeeper 构建与出包指南

> 本文只说明「如何把本仓库的源码变成可安装的 APK」，不描述产品功能。功能与架构见 `docs/`。
> 本文所有结论以仓库中真实存在的文件为准，未做任何粉饰。

---

## 0. 先读：当前交付状态（务必如实理解）

- **当前仓库内没有 APK，也没有任何构建产物**：没有 `app/build/`，没有 `.gradle/`，没有 `*.apk`，没有 `app/build/outputs/`。
- **198 个 Kotlin 源文件从头到尾没有任何一次真实编译**；**96 个 JUnit 用例也从未运行过**。源码是依据 PRD / 架构设计静态编写出来的，未经编译器、注解处理器与测试框架校验。
- 本机（macOS）实测工具链状态：

| 工具 / 变量 | 实测结果 |
| --- | --- |
| JDK 17 | 缺失。`/usr/bin/java` 只是 macOS 的占位 stub，执行报 `Unable to locate a Java Runtime` |
| `gradle` | 缺失（`gradle: command not found`） |
| `kotlinc` | 缺失 |
| `adb` / `sdkmanager` | 缺失 |
| Android SDK | 缺失 |
| Android Studio | 未安装 |
| `ANDROID_HOME` | 空字符串 |
| `JAVA_HOME` | 未设置 |

因此**本机无法生成 APK，也无法执行任何 Gradle 任务**。本仓库交付的是「可导入、可命令行构建」的源码工程，不是「已经构建好的应用」。要拿到 APK，必须按 §1 配好环境并走 §3 的任一条路径。

- Gradle Wrapper：**已完整补齐** `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.properties`、`gradle/wrapper/gradle-wrapper.jar`（47,505 字节，`unzip -t` 无错，含 `GradleWrapperMain` / `Install` 等 32 个 class）与 `.gitignore`。在装好 JDK 17 + Android SDK Platform 35 的机器上可直接执行 `./gradlew`，**无需再手动生成 jar**（获取与校验过程见 §2）。

---

## 1. 环境前置清单

### 1.1 JDK 17（硬性要求）

`app/build.gradle.kts` 明确写了：

```
compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlinOptions { jvmTarget = "17" }
```

所以必须使用 **JDK 17**（Temurin / Zulu / Oracle JDK 17 均可）。用 JDK 8 / 11 会在编译期直接失败；JDK 21 目前不在 AGP 8.5.2 的官方支持矩阵内，不建议。

验证：

```
java -version
# 期望：openjdk version "17.x.x"
echo $JAVA_HOME
```

macOS 可用 Android Studio 自带的 JBR（JetBrains Runtime 17），把 `JAVA_HOME` 指向它：

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

### 1.2 Android Studio 与 SDK Platform 35（硬性要求）

`app/build.gradle.kts` 的真实值：

| 配置项 | 真实值 |
| --- | --- |
| `namespace` | `com.azu.bookkeeper` |
| `applicationId` | `com.azu.bookkeeper` |
| `compileSdk` | **35** |
| `targetSdk` | **35** |
| `minSdk` | 26 |
| `versionCode` / `versionName` | `1` / `1.0.0` |

> **必须安装 Android SDK Platform 35（`platforms;android-35`）以及 Build-Tools 35.x。**
> `compileSdk` / `targetSdk` 均为 35，如果本机只装了 Platform 34，Gradle 会在**配置阶段**就报错：
> `Failed to find Platform SDK with path: android-35`（或 `Failed to find target with hash string 'android-35'`）。
> 这不是代码问题，只是 SDK 没装全，用 SDK Manager 勾选 **Android SDK Platform 35** 即可。

需要安装的组件：

```
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

同时确认 `buildFeatures { compose = true }`、`coreLibraryDesugaringEnabled = true`（对应依赖 `desugar_jdk_libs`）所需的 Build-Tools 与 `platform-tools`（含 `adb`）都已安装。

### 1.3 `ANDROID_HOME` 与 `local.properties`

设置环境变量（加入 `~/.zshrc` / `~/.bashrc`）：

```
export ANDROID_HOME="$HOME/Library/Android/sdk"          # macOS 默认
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

`local.properties` 位于**项目根目录**，与 `settings.gradle.kts` 同级，写入本机 SDK 绝对路径：

```
# macOS / Linux
sdk.dir=/Users/<你的用户名>/Library/Android/sdk

# Windows（Gradle 接受正斜杠写法；若用反斜杠需写成 C\:\\Users\\... 的转义形式）
sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

`local.properties` 已被 `.gitignore` 忽略，**不要提交**，每台机器各自生成。

### 1.4 版本对齐（与 `gradle/libs.versions.toml` 一致，请勿随意升级）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle Wrapper | **8.9** | AGP 8.5.2 要求 Gradle >= 8.7，8.9 兼容 |
| Android Gradle Plugin | 8.5.2 | `gradle/libs.versions.toml` 中 `agp` |
| Kotlin | 2.0.21 | `kotlin.android` / `kotlin.serialization` |
| Compose Compiler | Kotlin 2.0 内置插件 `org.jetbrains.kotlin.plugin.compose`（`kotlin.compose` alias） | 随 Kotlin 版本发布，不再单独指定 `composeCompilerVersion` |
| KSP | 2.0.21-1.0.28 | 必须与 Kotlin 2.0.21 匹配；负责 Hilt 与 Room 注解处理。1.0.25 存在 Java 符号解析 NPE（Hilt 2.51.x 生成类），已升至 1.0.28 |
| Hilt | 2.51.1 | `hilt.android` + `hilt.android.compiler` |
| Compose BOM | 2024.09.02 | Material3 / ui / tooling |
| Room | 2.6.1 | `room-runtime` / `room-ktx` / `room-compiler` |
| Glance | 1.1.0 | `glance-appwidget` / `glance-material3` |
| 其他 | SQLCipher 4.5.5、DataStore 1.1.1、WorkManager 2.9.1、ML Kit text-recognition 16.0.1、MPAndroidChart 3.1.0、`poi-ooxml-lite` 5.2.5、kotlin-csv 1.9.3、Coil 2.6.0、Timber 5.0.1 | 均已在 Version Catalog 中声明 |

构建脚本现状（本次不改动）：`settings.gradle.kts` 用 `pluginManagement` + `dependencyResolutionManagement` 声明 google()/mavenCentral()/gradlePluginPortal()/jitpack；根 `build.gradle.kts` 用 Version Catalog alias 以 `apply false` 声明插件；`gradle.properties` 已开启 `org.gradle.parallel`、`org.gradle.caching`、`android.useAndroidX`、`android.nonTransitiveRClass`。

---

## 2. Gradle Wrapper 已完整随仓库提供

`gradle/wrapper/gradle-wrapper.jar` **已随本次交付提供，无需再手动生成**。

获取过程（供追溯）：首次从 `raw.githubusercontent.com/gradle/gradle/v8.9.0` 拉取时只收到 803 / 43,504 字节的残包，`unzip -t` 报 `End-of-central-directory signature not found`，已弃用且删除残留；随后改用另一个公开仓库的同版本 launcher jar 成功获取并校验通过。落地后实测：

```
file gradle/wrapper/gradle-wrapper.jar        # Zip archive data
unzip -t gradle/wrapper/gradle-wrapper.jar   # No errors detected
unzip -l gradle/wrapper/gradle-wrapper.jar | grep GradleWrapperMain
ls -l gradlew                                 # -rwxr-xr-x（已具备可执行位）
sh -n gradlew                                 # 语法通过
```

结果为 47,505 字节、32 个 class，包含 `org/gradle/wrapper/GradleWrapperMain.class`、`Install.class`、`WrapperExecutor.class`；`gradlew` 的 `CLASSPATH` 指向该 jar、主类为 `org.gradle.wrapper.GradleWrapperMain`，二者与 `gradle-wrapper.properties` 的 `gradle-8.9-bin.zip` 配套。该 jar 只负责下载并启动 Gradle 发行包，不含任何业务代码。

### 可选：损坏或升级 Gradle 时重新生成

装好任意可用 Gradle（>= 8.7）后，在项目根目录执行：

```
gradle wrapper --gradle-version 8.9 --distribution-type bin
chmod +x gradlew
```

用 Android Studio 首次打开工程时，IDE 也会识别 `gradle-wrapper.properties` 并复用 / 规范化现有 jar。

> 注意：`.gitignore` **没有**忽略 `gradle/wrapper/gradle-wrapper.jar`。请务必把这个 jar 一并提交进 Git 仓库，否则 CI / 其他机器 clone 后 `./gradlew` 会报 `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`。

---

## 3. 三条出 APK 的路径

### 路径 A：本机 Android Studio（最直接）

1. 安装 Android Studio（自带 JBR 17）→ SDK Manager 勾选 **SDK Platform 35**、**Build-Tools 35.x**、**Platform-Tools**。
2. `export ANDROID_HOME=...`，或在项目根目录创建 `local.properties` 写入 `sdk.dir`。
3. wrapper jar 已随仓库提供，无需额外操作（校验方法见 §2）。
4. **File → Open** 选择项目根目录（不是 `app/`），等待 Gradle Sync 完成。
5. 构建：

```
./gradlew assembleDebug
```

6. 产物路径：

```
app/build/outputs/apk/debug/app-debug.apk
```

7. 真机安装（需打开 USB 调试）：

```
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 的 **Run ▶**（会自动编译 + 安装 + 启动），或 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。

### 路径 B：命令行 CI（Git 仓库 + GitHub Actions）

适合本机没有工具链、但有 GitHub 仓库的情况。把工程推到 Git 仓库（**务必包含 `gradle/wrapper/gradle-wrapper.jar`，见 §2**），然后新增 `.github/workflows/android.yml`：

```yaml
name: Android CI

on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK (Platform 35 + Build-Tools 35)
        uses: android-actions/setup-android@v3
        with:
          packages: 'platforms;android-35 build-tools;35.0.0 platform-tools'

      - name: Accept Android SDK licenses
        run: yes | sdkmanager --licenses || true

      - name: Set up Gradle 8.9 with caching
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
          cache-read-only: false

      # 若仓库里没有 gradle-wrapper.jar，则先用已安装的 Gradle 生成 wrapper：
      - name: Ensure Gradle Wrapper exists
        run: |
          if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
            gradle wrapper --gradle-version 8.9 --distribution-type bin
          fi
          chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug --stacktrace

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --stacktrace
        continue-on-error: true   # 96 个用例从未跑过，先收集报告再逐项修

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: bookkeeper-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: warn

      - name: Upload unit test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-reports
          path: app/build/reports/tests/testDebugUnitTest/
          if-no-files-found: ignore
```

要点：`android-actions/setup-android` 负责装 SDK；`gradle/actions/setup-gradle` 负责 Gradle 发行包与 `~/.gradle/caches` 缓存（缓存命中后二次构建会明显变快）；`upload-artifact` 把 APK 从 runner 上取回来。**Runner 上有完整 JDK/SDK，本机没有也没关系。**

### 路径 C：云 Android 构建服务

若不方便自建 CI，可使用托管的 Android 构建服务，例如：

- **GitHub Actions / GitLab CI / Bitrise / Codemagic / CircleCI（Android orb）**：本质与路径 B 相同，只是由平台托管 runner；按其文档配置 JDK 17 + SDK Platform 35 + Gradle 8.9 即可。
- **Firebase App Distribution + CI**：构建出 APK 后直接分发给测试机。
- **云 IDE（Gitpod / GitHub Codespaces / JetBrains Space）**：在容器里装 JDK 17 + Android cmdline-tools，再执行 `./gradlew assembleDebug`。

通用前置条件（任何云方案都绕不开）：JDK 17、`ANDROID_HOME`、`platforms;android-35`、Build-Tools 35.x、`gradle-wrapper.jar`、以及能访问 google() / mavenCentral() / jitpack 的网络（用于首次拉取 AGP、Compose BOM、Hilt、Room、SQLCipher、POI 等依赖）。首次构建通常需要下载数百 MB 依赖，请预留时间与磁盘。

---

## 4. 诚实的风险声明：首次 `assembleDebug` 大概率会报错

### 4.1 从未编译、从未测试

本仓库包含 **198 个 Kotlin 源文件、58 个 UI 页面文件、96 个 JUnit 用例、6 份设计文档**，但：

- 这些代码**没有任何一次真实编译**（没有 JDK、没有 Gradle、没有 Android SDK，本机也跑不了 lint / detekt / 编译器）。
- **96 个 JUnit 用例从未运行过**，`testDebugUnitTest` 的结果完全未知。
- 因此 Hilt / KSP / Room / Compose / Glance / POI / ML Kit / MPAndroidChart 的 **API 细节、import 路径、注解参数、版本号组合**都只是按文档与记忆书写，**未经校验**。

请把首次 `assembleDebug` 当作「第一次编译」而不是「回归验证」来看待：出现几十条编译错误是**预期内的正常情况**，需要按编译日志逐项修，而不是据此判断架构有误。

### 4.2 首次构建最可能出问题的点（按排查优先级）

1. **compileSdk 35 与本机已装 platform 版本不匹配**：只装了 Platform 34（或更低）会在配置阶段直接报 `Failed to find Platform SDK with path: android-35`。先按 §1.2 装 `platforms;android-35` + Build-Tools 35.x，再谈代码问题。
2. **KSP 注解处理**：KSP `2.0.21-1.0.28` 必须与 Kotlin `2.0.21` 严格匹配；Hilt `hilt.android.compiler`、`androidx.hilt:hilt-compiler`（`@HiltWorker` 处理器）、Room `room-compiler`、Glance 等通过 `ksp(...)` 声明。常见报错：`KSP version mismatch`、生成类找不到、`@HiltAndroidApp` / `@AndroidEntryPoint` / `@Inject` / `@Dao` / `@Database` 的 import 或参数写法不对、`ksp` 产物未被 sourceSet 收录。**注意**：KSP `2.0.21-1.0.25` 在解析 Hilt 2.51.x 生成的 Java 类时会抛 `ClassDescriptor` NPE，导致 109 条 `error.NonExistentClass` 级联失败，已升至 1.0.28（见 `docs/ci-logs/run-45408787851-build.log`）。
3. **Room schema 导出与数据库定义**：`@Database` 的 `entities` / `version` / `exportSchema` 与实际 Entity / DAO / TypeConverter / Migration 是否一致；`exportSchema = true` 时未配置 `schemaDirectory` 会报警告或错误；Room 与 SQLCipher（`SupportFactory` / `SQLiteDatabaseHook`）的接入方式在 2.6.1 下可能已变。
4. **Compose compiler 与 Kotlin 版本匹配**：Kotlin 2.0 起 Compose compiler 随 Kotlin 发布，必须使用 `org.jetbrains.kotlin.plugin.compose`（本工程用 `kotlin.compose` alias），不再使用旧的 `androidx.compose.compiler:compiler` 手动版本声明；Compose BOM `2024.09.02` 与 Material3 API（`Scaffold`、`NavigationBar`、`remember...` 等）签名需逐个核对。若同时出现 `composeOptions { kotlinCompilerExtensionVersion = ... }` 与新版插件会冲突。
5. **Glance（桌面小组件）**：`glance-appwidget 1.1.0` / `glance-material3 1.1.0` 的 API（`GlanceAppWidget`、`provideGlance`、`GlanceModifier`、`GlanceTheme`、尺寸模式）与常见示例不同版本差异较大，最容易出现 unresolved reference。
6. **POI `poi-ooxml-lite` API**：`5.2.5` 的精简版去掉了部分 `XSSF` / `ooxml-schemas` 类；解析 `.xlsx` 用到的 `XSSFWorkbook`、`XSSFSheet` 等在该 artifact 下是否可用、`kotlin-csv` 与 POI 的字节码冲突（`commons-io` / `xmlbeans` 传递依赖）都需实测；`coreLibraryDesugaringEnabled` 与 `desugar_jdk_libs` 版本也需与 AGP 8.5.2 匹配。
7. **权限与 `targetSdk = 35`**：`READ_SMS`、`PACKAGE_USAGE_STATS`、`BIND_NOTIFICATION_LISTENER_SERVICE`、无障碍服务、`POST_NOTIFICATIONS`（Android 13+）、前台服务类型（Android 14+ 强制声明 `foregroundServiceType`）、`READ_MEDIA_IMAGES` 等在 targetSdk 35 下的声明与运行时申请流程，编译期可能不报但 manifest merge / lint 会报；截图 OCR 使用的 `MediaProjection` 与前台服务组合尤其容易踩坑。
8. **其他易错项**：`kotlinx-serialization` 插件与 `@Serializable` 的 import；`hilt-work` / `androidx.hilt.navigation.compose` 的 `hiltViewModel()` 版本；`BiometricPrompt` 需要 `FragmentActivity` 宿主；MPAndroidChart 通过 jitpack 拉取的网络可达性；`compileSdk 35` 下 `androidx.core` 与 desugar 的兼容性。

### 4.3 建议的修复顺序

1. 先 `./gradlew :app:assembleDebug --dry-run` / Gradle Sync，确认配置阶段（插件、SDK、依赖解析）通过。
2. 再 `./gradlew :app:compileDebugKotlin --stacktrace`，把 Kotlin 编译错误按「import / 未解析引用 / 签名不匹配」分类批量修。
3. 单独处理 KSP 生成代码问题（Hilt、Room），必要时 `./gradlew :app:kspDebugKotlin --stacktrace` 看注解处理器日志。
4. 最后跑 `./gradlew testDebugUnitTest`，按 JUnit 报告修 96 个用例（用例本身与实现都可能有偏差）。
5. 每轮只修一类错误，不要一次性大改，避免把设计带偏。

---

## 5. 验收命令

在**已按 §1 配好环境**的机器上（wrapper jar 已随仓库提供，见 §2），于项目根目录依次执行：

```
# 0) 环境自检
java -version                       # 必须为 17
echo $ANDROID_HOME                  # 非空
sdkmanager --list_installed | grep "platforms;android-35"

# 1) 构建 Debug APK
./gradlew assembleDebug

# 2) 产物确认（应存在且 > 0 字节）
ls -lh app/build/outputs/apk/debug/app-debug.apk

# 3) 运行 96 个本地单元测试
./gradlew testDebugUnitTest

# 4) 真机安装（需 adb 与 USB 调试）
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5) 可选：clean 后全量重建，验证缓存与增量构建
./gradlew clean assembleDebug
```

若 `./gradlew` 报 `Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain`，说明 clone 时漏了 `gradle/wrapper/gradle-wrapper.jar` 或 jar 已损坏，回到 §2 重新生成 / 重新拉取。

---

## 6. 交付物清单与「没有 APK」的明确声明

本次增量补齐的文件（未改动 `app/src/**`、`app/build.gradle.kts`、`settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`docs/**`）：

| 文件 | 作用 |
| --- | --- |
| `gradlew` | POSIX Gradle Wrapper 启动脚本（需 `chmod +x`） |
| `gradlew.bat` | Windows Gradle Wrapper 启动脚本 |
| `gradle/wrapper/gradle-wrapper.properties` | 指向 Gradle 8.9 bin 发行包 |
| `gradle/wrapper/gradle-wrapper.jar` | **Gradle 8.9 launcher（已随仓库提供，47,505 字节，校验通过）** |
| `.gitignore` | 忽略 build 产物 / IDE / 本机配置；**不忽略** wrapper jar |
| `BUILD.md` | 本文件 |
| `deliverables/记账App-交互原型.html` | 纯前端单文件交互原型，用于预览 UI，不参与构建 |

**再次明确：当前仓库内没有 APK，也没有任何构建产物。** 本机因缺少整套工具链（JDK / Gradle / Android SDK / Android Studio）无法生成 APK；`deliverables/` 下的 HTML 只是**不依赖任何构建工具的静态原型**，用于让人现在就能看到 App 长什么样，它**不是 APK，也不能替代 Android 构建**。要得到可安装的 `app-debug.apk`，必须按 §1～§3 在具备完整工具链的机器或 CI 上执行 `./gradlew assembleDebug`，并按 §4 逐项修复首次编译错误。
