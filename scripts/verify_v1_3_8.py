#!/usr/bin/env python3
"""v1.3.8 静态一致性测试：在 v1.3.7 全量回归基础上，新增红包四场景、聊天页排除、内部划转与识屏提醒检查。"""
import os, sys, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

passed = 0
failed = []

def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()

def check(name, condition, note=""):
    global passed
    if condition:
        passed += 1
        print("PASS", name)
    else:
        failed.append(name)
        print("FAIL", name, note)

# ---------- 1. 版本号一致 ----------
gradle = read("app/build.gradle.kts")
workflow = read(".github/workflows/android.yml")
constants = read("app/src/main/java/com/azu/bookkeeper/core/Constants.kt")
check("T-VER-01 versionCode 15", "versionCode = 15" in gradle)
check("T-VER-02 versionName 1.3.8", 'versionName = "1.3.8"' in gradle)
check("T-VER-03 Release tag v1.3.8", "tag_name: v1.3.8-debug" in workflow)
check("T-VER-04 DATABASE_VERSION 2", "DATABASE_VERSION = 2" in constants)

# ---------- 1.1 固定调试签名（解决覆盖安装签名不一致） ----------
check("T-SIGN-01 固定调试签名配置", 'create("stableDebug")' in gradle)
check("T-SIGN-02 调试密钥库文件存在", os.path.isfile("keystore/bookkeeper-debug.p12"))
check("T-SIGN-03 debug 使用固定签名",
      'signingConfig = signingConfigs.getByName("stableDebug")' in gradle)

# ---------- 1.2 无障碍健康检查与异常日志（v1.2.2 真机日志驱动的修复） ----------
a11y_health = read("app/src/main/java/com/azu/bookkeeper/automation/permission/AccessibilityHealth.kt")
service = read("app/src/main/java/com/azu/bookkeeper/automation/service/PaymentAccessibilityService.kt")
log = read("app/src/main/java/com/azu/bookkeeper/core/CaptureLog.kt")
rom = read("app/src/main/java/com/azu/bookkeeper/core/RomCompat.kt")
guide = read("app/src/main/java/com/azu/bookkeeper/automation/permission/PermissionGuide.kt")
settings_ui = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AutomationSettingsScreen.kt")
about = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AboutScreen.kt")
check("T-A11Y-01 无障碍健康检查文件", "object AccessibilityHealth" in a11y_health)
check("T-A11Y-02 强制日志入口", "fun force(" in log)
check("T-A11Y-03 服务连接上报健康状态", "AccessibilityHealth" in service)
check("T-A11Y-04 关于页含 v1.3.8 介绍", "v1.3.8" in about)

# vivo / iQOO 必须命中保活引导（真机 V2505A 原本完全看不到卡片）
check("T-ROM-01 识别 vivo / iQOO", "fun isVivoFamily(): Boolean" in rom)
check("T-ROM-02 保活判断覆盖 vivo",
      "isOppoFamily() || isOrangeOs() || isVivoFamily()" in rom)
check("T-ROM-03 设置页调用新判断", "needsKeepAliveGuidance()" in settings_ui)
check("T-ROM-04 卡片标题按厂商显示", "fun keepAliveLabel()" in rom and "keepAliveLabel()" in settings_ui)

# 诊断必须能自动判因，而不是只堆日志
check("T-DIAG-01 自动诊断结论", "private fun diagnose(" in a11y_health)
check("T-DIAG-02 侧载受限设置引导", "允许受限设置" in a11y_health)
check("T-DIAG-03 vivo 后台高耗电与一键加速白名单",
      "后台高耗电" in a11y_health and "一键加速白名单" in a11y_health)
check("T-DIAG-04 安装来源进报告", "fun installSourceText()" in guide and "installSourceText()" in a11y_health)
check("T-DIAG-05 累计被撤销次数", "KEY_REVOKE_COUNT" in a11y_health)
check("T-DIAG-06 服务存活时长进报告", "本次服务存活" in a11y_health)

# 日志时间线：连接也要落盘，误导性措辞必须消失
check("T-LOG-01 INFO 级强制落盘", "fun forceInfo(" in log)
check("T-LOG-02 连接事件强制落盘", "forceInfo(" in service and "服务已连接 #" in service)
check("T-LOG-03 旧误导文案已移除", "被系统销毁/进程退出" not in service)
check("T-LOG-04 记录存活时长与 pid", "KEY_LAST_LIFESPAN" in service and "Process.myPid()" in service)
check("T-LOG-05 解绑措辞准确", "非崩溃证据" in service and "解绑后的正常销毁" in service)

# ---------- 1.3 v1.2.3 侧载受限设置与最小权限 ----------
xml = read("app/src/main/res/xml/accessibility_service_config.xml")
diag_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/CaptureDiagnosticsViewModel.kt")
adb_script_path = "scripts/android_unlock_accessibility.sh"
check("T-SIDE-01 侧载识别含 packageinstaller", "packageinstaller" in a11y_health)
check("T-SIDE-02 ADB 命令可复制",
      "fun adbUnlockText()" in a11y_health and "ACCESS_RESTRICTED_SETTINGS allow" in a11y_health)
check("T-SIDE-03 Android 17 高级保护模式引导", "高级保护模式" in a11y_health)
check("T-SIDE-04 pid 稳定性判因",
      "pidStable" in a11y_health and "KEY_PID_CHANGED" in service
      and "fun accessibilityPidStable()" in guide)
check("T-SIDE-05 最小权限 XML 移除高风险声明",
      "android:packageNames" not in xml and "android:canTakeScreenshot" not in xml
      and 'android:canRetrieveWindowContent="true"' in xml)
check("T-SIDE-06 保留读节点与指纹手势 flag",
      "flagRetrieveInteractiveWindows" in xml and "flagRequestFingerprintGestures" in xml)
check("T-SIDE-07 ADB 一键解锁脚本存在",
      os.path.isfile(adb_script_path) and "appops set" in read(adb_script_path))
check("T-SIDE-08 无截屏能力不再误报", "capabilityRemovedByDesign" in diag_vm)

# ---------- 1.4 v1.3.0 识屏准确度与自动化 ----------
recognizer = read("app/src/main/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizer.kt")
recognizer_test_path = "app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt"
a11y_parser = read("app/src/main/java/com/azu/bookkeeper/automation/capture/AccessibilityCaptureParser.kt")
notif_parser = read("app/src/main/java/com/azu/bookkeeper/automation/capture/NotificationCaptureParser.kt")
coordinator = read("app/src/main/java/com/azu/bookkeeper/automation/capture/CaptureCoordinator.kt")
overlay = read("app/src/main/java/com/azu/bookkeeper/automation/verify/OverlayVerifyView.kt")
shake = read("app/src/main/java/com/azu/bookkeeper/ui/quick/ShakeQuickAddService.kt")
verifier = read("app/src/main/java/com/azu/bookkeeper/automation/verify/VerificationController.kt")
check("T-REC-01 页面识别器区分四种页面",
      "enum class PaymentScreen" in recognizer and "ENTRY, SUCCESS, DETAIL, UNKNOWN" in recognizer)
check("T-REC-02 Activity 类名强信号与微信空节点重读",
      "looksLikeResultClass" in recognizer and "scheduleResultRetry" in service)
check("T-REC-03 微信文案与金额商户标签化",
      "已存入对方零钱" in recognizer and "金额：" in recognizer and "商户：" in recognizer)
check("T-REC-04 子进程包名归一化", "substringBefore(':')" in recognizer)
check("T-REC-05 移除解析前占位的旧去抖",
      "lastEventAt" not in service and "CAPTURE_DEBOUNCE_MS" not in service)
check("T-REC-06 多根节点读取与主线程文本快照",
      "getWindows()" in service and "readScreenText" in service)
check("T-REC-07 通知补读 bigText/subText",
      "android.bigText" in notif_parser and "android.subText" in notif_parser)
check("T-REC-08 统一采集器深度 32 且 text/desc 都收",
      "MAX_DEPTH = 32" in read("app/src/main/java/com/azu/bookkeeper/automation/capture/ScreenTextCollector.kt")
      and "contentDescription" in read("app/src/main/java/com/azu/bookkeeper/automation/capture/ScreenTextCollector.kt"))
check("T-REC-09 支付成功页置信度加成",
      "screenBonus" in coordinator and "PaymentScreen.SUCCESS.name" in coordinator)
check("T-REC-10 页面识别单测存在",
      os.path.isfile(recognizer_test_path)
      and "微信支付结果页只有类名也能识别为成功页" in read(recognizer_test_path))
check("T-OVL-01 底部悬浮卡片",
      "Gravity.BOTTOM" in overlay and "MATCH_PARENT" in overlay
      and "FLAG_NOT_TOUCH_MODAL" in overlay)
check("T-OVL-02 重复确认不再拉起软件界面",
      "showDuplicateConfirm" in shake and "overlayVerify()" in shake
      and "startActivity(" not in shake.split("private fun showDuplicateConfirm")[1]
      .split("private fun autoRecord")[0])
check("T-OVL-03 无权限时降级为点击才打开的通知",
      "showConfirmFallbackNotification" in shake and "setContentIntent" in shake)
check("T-OVL-04 校对链优先底部卡片且无默认常驻通知",
      "notifyRecorded" in verifier and "showTransientVerify" in verifier and "showVerify(candidate)" not in verifier)



# ---------- 1.2 v1.3.2：重复规则 / 弹窗样式 / 文案精简 ----------
dedup_rules = read("app/src/main/java/com/azu/bookkeeper/automation/dedup/DuplicateRules.kt")
auto_record = read("app/src/main/java/com/azu/bookkeeper/domain/usecase/bill/AutoRecordUseCase.kt")
analyzer = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/PaymentTextAnalyzer.kt")
watchdog = read("app/src/main/java/com/azu/bookkeeper/automation/service/A11yWatchdogService.kt")
health = read("app/src/main/java/com/azu/bookkeeper/automation/permission/AccessibilityHealth.kt")
check("T-132-01 重复准入规则存在", "object DuplicateRules" in dedup_rules and "isValidPair" in dedup_rules)
check("T-132-02 无效金额 / 泛化商户不判重复",
      "DuplicateRules.isValidPair(candidate.amountFen, remark)" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/DuplicateDetector.kt")
      and "DuplicateRules.isValidPair(amountFen, name)" in auto_record
      and "DuplicateRules.isValidPair(amountFen, merchant)" in coordinator)
check("T-132-03 时间窗命中后严格比对金额与账户",
      "windowHit.amountFen != amount" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/DuplicateDetector.kt")
      and "windowHit.accountFrom != accountId" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/DuplicateDetector.kt"))
check("T-132-04 同一旧账单只提醒一次", "hintedBillIds" in auto_record and "hintedBillIds.add(hit.billId)" in auto_record)
check("T-132-05 非实付金额被排除", "NON_PAYMENT_AMOUNT_HINTS" in analyzer and "余额" in analyzer)
check("T-132-06 弹窗使用 App 主题色", ".toArgb()" in overlay and "PrimaryDark" in overlay and "LightOnSurface" in overlay)
check("T-132-07 弹窗按钮改为胶囊样式", "cornerRadius = dp(999).toFloat()" in overlay and "android.widget.Button" not in overlay)
check("T-132-08 更新介绍已精简（含 v1.3.8 共 15 版 + 函数定义）", about.count("ChangelogRow(") <= 16)
check("T-132-09 重复规则单测存在",
      os.path.isfile("app/src/test/java/com/azu/bookkeeper/automation/dedup/DuplicateRulesTest.kt"))

check("T-132-10 校对候选补齐默认分类",
      "fallbackCategoryId" in coordinator and "?: fallbackCategoryId(" in coordinator)
check("T-132-11 轻卡片确认失败有提示",
      "记账未完成" in verifier and "result.error.userMessage" in verifier)
check("T-132-12 守护检查写回并验证",
      "KEY_RESTORE_FAILS" in watchdog and "守护写回授权后系统再次撤销" in watchdog and
      "val restored" in watchdog)
check("T-132-14 通知栏只留自动记账一条",
      "NOTIFICATION_ID = Constants.FOREGROUND_SERVICE_ID" in shake and
      "MAINTENANCE_INTERVAL_MS = 15_000L" in read(
          "app/src/main/java/com/azu/bookkeeper/automation/service/AutoCaptureForegroundService.kt") and
      "A11yWatchdogService.checkNow" in read(
          "app/src/main/java/com/azu/bookkeeper/automation/service/AutoCaptureForegroundService.kt"))
check("T-132-13 诊断含自动恢复权限",
      "canAutoRestore" in health and "自动恢复权限" in health)

# ---------- 1.1 v1.3.1：静默入账 / 统一采集 / 轻弹窗 / 守护 / 自动更新 ----------
collector = read("app/src/main/java/com/azu/bookkeeper/automation/capture/ScreenTextCollector.kt")
watchdog = read("app/src/main/java/com/azu/bookkeeper/automation/service/A11yWatchdogService.kt")
health = read("app/src/main/java/com/azu/bookkeeper/automation/permission/AccessibilityHealth.kt")
adb_script = read("scripts/android_unlock_accessibility.sh")
manifest = read("app/src/main/AndroidManifest.xml")
file_paths = read("app/src/main/res/xml/file_paths.xml")
updater = read("app/src/main/java/com/azu/bookkeeper/core/update/GithubUpdateChecker.kt")
installer = read("app/src/main/java/com/azu/bookkeeper/core/update/UpdateInstaller.kt")
about_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AboutViewModel.kt")
lightweight = read("app/src/main/java/com/azu/bookkeeper/ui/quick/LightweightCapture.kt")

check("T-131-01 统一文本采集器存在", "object ScreenTextCollector" in collector)
def _code_lines(text):
    return [line for line in text.splitlines()
            if not line.strip().startswith(("*", "//", "/*"))]

check("T-131-02 不再回收共享子节点",
      not any("recycle(" in line for line in _code_lines(collector)) and
      not any("recycle(" in line for line in _code_lines(a11y_parser)))
check("T-131-03 多段文本按行去重", "LinkedHashSet<String>" in collector and "fun merge(" in collector)
check("T-131-04 稀疏文本检测接入识屏", "fun isSparse(" in collector and "isSparse(text)" in service)
check("T-131-05 两条链路共用 refine", "ScreenTextCollector.refine" in service)
check("T-131-06 账单详情不再直接丢弃", "PaymentScreen.DETAIL ->" in service and "提交去重" in service)
check("T-131-07 抖音订单页识别", "DOUYIN_RESULT_CLASS_TOKENS" in recognizer and "实付" in recognizer and "refineDouyinText" in recognizer)
check("T-131-08 自动链路统一抽取并合成备注",
      "PaymentTextAnalyzer" in coordinator and "composeRemark(bestMerchant, analysis.goods)" in coordinator)
check("T-131-09 高置信度静默入账后有轻卡片成功提醒", "notifyRecorded" in coordinator and "showSuccess" in overlay)
check("T-131-10 确定重复改为轻提示", "notifyDuplicated" in coordinator and "这笔已记过" in verifier)
check("T-131-11 校对链不再默认发常驻通知",
      "showVerify(candidate)" not in verifier and "showTransientVerify" in verifier)
check("T-131-12 轻卡片用无障碍窗口类型",
      "TYPE_ACCESSIBILITY_OVERLAY" in overlay and "PaymentAccessibilityService.current()" in overlay)
check("T-131-13 自动入账成功不再发常驻通知",
      "showRecorded(" not in shake and "progress.clear()" in lightweight)
check("T-131-14 无障碍守护已并入自动记账服务",
      "class A11yWatchdogService" in watchdog and "fun checkNow(" in watchdog and "startForeground" not in watchdog)
check("T-131-15 WRITE_SECURE_SETTINGS 自动恢复", "tryRestore" in health and "WRITE_SECURE_SETTINGS" in health)
check("T-131-16 ADB 脚本支持 grant/restore", "grant)" in adb_script and "restore)" in adb_script)
check("T-131-17 关于页更新检查依赖与权限",
      "object GithubUpdateChecker" in updater and "object UpdateInstaller" in installer and
      "class AboutViewModel" in about_vm and "android.permission.INTERNET" in manifest and
      "android.permission.REQUEST_INSTALL_PACKAGES" in manifest)
check("T-131-18 FileProvider 开放 updates 目录", 'name="updates"' in file_paths and "getUriForFile" in installer)
check("T-131-19 关于页更新入口已更名", "else -> \"检查更新\"" in about and "updateViewModel" in about and "从 GitHub Releases" not in about)
check("T-131-20 版本号 v1.3.8 进入 Release 正文", "v1.3.8-debug" in workflow and "v1.3.8" in about)
check("T-131-21 新增页面识别单测覆盖抖音",
      os.path.isfile("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt")
      and "抖音订单页只有实付也能识别为成功页" in
      read("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt"))
check("T-131-22 统一采集与更新检查单测存在",
      os.path.isfile("app/src/test/java/com/azu/bookkeeper/automation/capture/ScreenTextCollectorTest.kt")
      and os.path.isfile("app/src/test/java/com/azu/bookkeeper/core/update/GithubUpdateCheckerTest.kt"))
check("T-131-23 测试文档已建",
      os.path.isfile("docs/testing/v1.3.8/TEST_CASES.md")
      and os.path.isfile("docs/testing/v1.3.8/TEST_REPORT.md"))

# ---------- 1.5 v1.3.3：更新下载排障 ----------
about_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AboutViewModel.kt")
installer = read("app/src/main/java/com/azu/bookkeeper/core/update/UpdateInstaller.kt")
update_test = "app/src/test/java/com/azu/bookkeeper/core/update/GithubUpdateCheckerTest.kt"
check("T-133-01 下载结果带具体错误", "data class DownloadOutcome" in updater and "val error: String?" in updater)
check("T-133-02 asset id 与双通道下载地址",
      "releases/assets/" in updater and "fallbackUrl" in updater and "browserUrl" in updater)
check("T-133-03 手动逐跳跟随重定向",
      "instanceFollowRedirects = false" in updater and 'conn.getHeaderField("Location")' in updater
      and "HTTP $code @ ${hostOf(current)}" in updater)
check("T-133-04 APK 文件头校验与临时文件",
      "looksLikeApk" in updater and '"${target.name}.part"' in updater)
check("T-133-05 代理域名放行提示", "githubusercontent.com" in updater and "fun hint(" in updater)
check("T-133-06 UI 显示具体下载错误",
      "下载失败：${result.error}" in about_vm and "messageIsError" in about_vm)
check("T-133-07 浏览器下载兜底", "fun openInBrowser" in about_vm and "用浏览器下载" in about)
check("T-133-08 本地 APK 安装兜底",
      "fun installLocalApk" in about_vm and "fun installFromUri" in installer and "选本地 APK" in about)
check("T-133-09 下载通道单测补充",
      os.path.isfile(update_test) and "错误提示" in read(update_test))

# ---------- 1.6 v1.3.4：识屏静默 + 奶油风弹窗 + 主题图标 ----------
service = read("app/src/main/java/com/azu/bookkeeper/automation/service/PaymentAccessibilityService.kt")
verifier = read("app/src/main/java/com/azu/bookkeeper/automation/verify/VerificationController.kt")
piggy_icon = "app/src/main/res/drawable/ic_piggy_overlay.xml"
check("T-134-01 同轮支付静默锁存在",
      "settledPackages" in service and "SETTLED_QUIET_MS" in service)
check("T-134-02 静默期拦提交 + ENTRY 解锁",
      "支付静默期跳过" in service and "settledPackages.remove(packageName)" in service)
check("T-134-03 重复提醒节流",
      "lastDuplicateNoticeAt" in verifier and "DUPLICATE_NOTICE_COOLDOWN_MS" in verifier)
check("T-134-04 弹窗奶油风与小猪 IP",
      "ic_piggy_overlay" in overlay and "记下啦" in overlay and "奶油风" in overlay)
check("T-134-05 弹窗暖心短句", "今天也有认真记账哦" in overlay and "note:" in overlay)
check("T-134-06 主题图标资源存在", os.path.isfile(piggy_icon) and "piggy" in piggy_icon)

# ---------- 1.7 v1.3.5：消费记录识别 / 黑屏 / 安装包清理 / 更可爱的弹窗 ----------
bill_edit = read("app/src/main/java/com/azu/bookkeeper/ui/bill/BillEditScreen.kt")
shake_svc = read("app/src/main/java/com/azu/bookkeeper/ui/quick/ShakeQuickAddService.kt")
auth_act = read("app/src/main/java/com/azu/bookkeeper/ui/quick/ScreenCaptureAuthActivity.kt")
confirm_act = read("app/src/main/java/com/azu/bookkeeper/ui/quick/QuickConfirmActivity.kt")
check("T-135-01 消费记录详情页强字段识别",
      "DETAIL_STRONG" in recognizer and "交易单号" in recognizer and "商户单号" in recognizer)
check("T-135-02 无成功词的凭证页兜底提交",
      "fun looksLikeReceipt(" in recognizer and "looksLikeReceipt(text)" in service
      and "凭证页兜底提交" in service)
check("T-135-03 静默期按金额指纹放行",
      "SettledMark" in service and "firstMoneyKey" in service and "mark.money" in service)
check("T-135-04 重试链去并发", "retryGeneration" in service)
check("T-135-05 表单无上一页时回首页",
      "val exitForm" in bill_edit and "popUpTo(Screen.BillEdit.route)" in bill_edit
      and "popBackStack()" in bill_edit)
check("T-135-06 识屏失败不再自动顶起表单",
      "openCaptureFormNow" in shake_svc and "showTapFallbackNotification" in shake_svc
      and "setFullScreenIntent(pending, true)" not in shake_svc)
check("T-135-07 透明页超时自毁",
      "finishOnce" in auth_act and "AUTH_TIMEOUT_MS" in auth_act
      and "CONFIRM_TIMEOUT_MS" in confirm_act and "timeoutHandler" in confirm_act)
check("T-135-08 安装后清理安装包",
      "scheduleCleanup" in installer and "cleanupOldApks" in installer
      and "cleanupOldApks(context)" in about_vm)
check("T-135-09 弹窗渐变与入场动画",
      "GradientDrawable.Orientation.TOP_BOTTOM" in overlay and ".animate()" in overlay
      and "圆形" in overlay)

# ---------- 1.8 v1.3.6：转账收款识别 / 支付前缓存 / 收入方向 ----------
event_kt = read("app/src/main/java/com/azu/bookkeeper/automation/capture/CaptureEvent.kt")
parser_kt = read("app/src/main/java/com/azu/bookkeeper/automation/capture/AccessibilityCaptureParser.kt")
pending_kt_path = "app/src/main/java/com/azu/bookkeeper/automation/capture/PendingPaymentCache.kt"
candidate_kt = read("app/src/main/java/com/azu/bookkeeper/domain/model/CaptureCandidate.kt")
check("T-136-01 收款方向词覆盖已收钱",
      "已收钱" in recognizer and "TRANSFER_IN_HINTS" in recognizer and "BillDirection" in recognizer)
check("T-136-02 转账中间态不记账",
      "待确认收款" in recognizer and "transferPending" in recognizer)
check("T-136-03 跨行紧凑匹配", 'replace("\\n", "")' in recognizer and "compact" in recognizer)
check("T-136-04 支付前缓存存在",
      os.path.isfile(pending_kt_path) and "object PendingPaymentCache" in read(pending_kt_path)
      and "PendingPaymentCache.remember" in service)
check("T-136-05 成功页带缓存补齐",
      "pendingAmountFen" in event_kt and "pendingMerchant" in event_kt
      and "event.pendingAmountFen" in coordinator and "event.pendingMerchant" in coordinator)
check("T-136-06 收款记收入",
      "billType: BillType" in candidate_kt and "BillType.INCOME" in coordinator
      and "candidate.billType" in coordinator)
check("T-136-07 收入分类兜底",
      "defaultIncomeCategoryId" in coordinator and "CategoryKind.INCOME" in coordinator)
check("T-136-08 转账场景枚举",
      "enum class PaymentScene" in recognizer and "WECHAT_TRANSFER_IN" in recognizer
      and "ALIPAY_TRANSFER_OUT" in recognizer)
check("T-137-01 asset 接口 Accept 精确设置",
      "if (isAssetApi) \"application/octet-stream\" else" in updater and "isAssetApi" in updater)
check("T-137-02 返回 JSON 时解析直链继续",
      "contentType.contains(\"json\"" in updater and "browserDownloadUrl" in updater)
check("T-136-09 转账单测覆盖",
      "微信接收转账成功记收入" in read("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt"))

# ---------- 1.4 v1.3.8：红包四场景 / 聊天页排除 / 内部划转 / 识屏提醒 ----------
recognizer = read("app/src/main/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizer.kt")
capture_event = read("app/src/main/java/com/azu/bookkeeper/automation/capture/CaptureEvent.kt")
coordinator = read("app/src/main/java/com/azu/bookkeeper/automation/capture/CaptureCoordinator.kt")
watchdog = read("app/src/main/java/com/azu/bookkeeper/automation/service/A11yWatchdogService.kt")
health_notifier = read("app/src/main/java/com/azu/bookkeeper/automation/service/CaptureHealthNotifier.kt")
strings = read("app/src/main/res/values/strings.xml")
recognizer_test = read("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt")

out_block = recognizer.split("private val TRANSFER_OUT_HINTS")[1].split(")")[0]
in_block = recognizer.split("private val TRANSFER_IN_HINTS")[1].split(")")[0]

check("T-138-01 红包收/发信号分表",
      "RED_PACKET_IN_HINTS" in recognizer and "RED_PACKET_OUT_HINTS" in recognizer)
check("T-138-02 红包判定需上下文双条件",
      "private fun redPacketHit" in recognizer and "RED_PACKET_CONTEXT" in recognizer)
check("T-138-03 对方已收款归入转出方向",
      "对方已收款" in out_block and "对方已收款" not in in_block)
check("T-138-04 聊天页整页排除", "CHAT_CLASS_TOKENS" in recognizer and "chatPage" in recognizer)
check("T-138-05 红包编辑页判中间态", "LuckyMoneyPrepare" in recognizer and "LuckyMoneyEdit" in recognizer)
check("T-138-06 提现充值判内部划转", "WITHDRAW_HINTS" in recognizer and "INTERNAL_TRANSFER" in recognizer)
check("T-138-07 云闪付进白名单", '"com.unionpay"' in constants)
check("T-138-08 场景贯通到捕获事件",
      "sceneType" in capture_event and "scene = recognition.scene" in service)
check("T-138-09 备注加场景前缀",
      "withScenePrefix" in coordinator and "remarkPrefix" in coordinator)
check("T-138-10 内部划转不入账",
      "notifyInternalTransfer" in coordinator and "BillDirection.NEUTRAL.name" in coordinator)
check("T-138-11 识屏不可用通知组件", "object CaptureHealthNotifier" in health_notifier)
check("T-138-12 通知带记一笔入口",
      "QuickCaptureLauncher.pendingIntent" in health_notifier and "ACTION_ACCESSIBILITY_SETTINGS" in health_notifier)
check("T-138-13 守护推送识屏提醒", "CaptureHealthNotifier.notifyUnavailable" in watchdog)
check("T-138-14 恢复后撤销提醒",
      "CaptureHealthNotifier.clear" in watchdog and "CaptureHealthNotifier.clear" in service)
check("T-138-15 独立通知渠道",
      'CHANNEL_CAPTURE_HEALTH = "channel_capture_health"' in constants)
check("T-138-16 通知文案资源", 'name="capture_health_title"' in strings)
check("T-138-17 单测覆盖红包两方向",
      "WECHAT_RED_PACKET_IN" in recognizer_test and "WECHAT_RED_PACKET_OUT" in recognizer_test)

# ---------- 2. 迁移正确性 ----------
migration = read("app/src/main/java/com/azu/bookkeeper/data/local/db/Migrations.kt")
check("T-MIG-01 迁移 1→2", "Migration(1, 2)" in migration)
check("T-MIG-02 accounts 加 bill_day", 'ADD COLUMN bill_day' in migration)
check("T-MIG-03 accounts 加 due_day", 'ADD COLUMN due_day' in migration)
check("T-MIG-04 accounts 加 reminder_enabled", 'ADD COLUMN reminder_enabled' in migration)
check("T-MIG-05 加列先查 PRAGMA", 'PRAGMA table_info' in migration)
for table in ["refunds", "reimbursements", "debts", "debt_repayments",
              "installments", "installment_payments", "annual_budgets"]:
    check(f"T-MIG-TBL-{table}", f"CREATE TABLE IF NOT EXISTS {table}" in migration)
check("T-MIG-06 迁移注册到 Database", "addMigrations(MIGRATION_1_2)" in
      read("app/src/main/java/com/azu/bookkeeper/core/di/DatabaseModule.kt"))

# ---------- 3. 实体与 DAO 注册一致 ----------
db = read("app/src/main/java/com/azu/bookkeeper/data/local/db/BookkeeperDatabase.kt")
for entity in ["RefundEntity", "ReimbursementEntity", "DebtEntity", "DebtRepaymentEntity",
               "InstallmentEntity", "InstallmentPaymentEntity", "AnnualBudgetEntity"]:
    check(f"T-DB-ENT-{entity}", f"{entity}::class" in db)
for dao in ["RefundDao", "ReimbursementDao", "DebtDao", "InstallmentDao", "AnnualBudgetDao"]:
    check(f"T-DB-DAO-{dao}", f"fun {dao[0].lower()+dao[1:]}()" in db)
    check(f"T-DI-{dao}", f"fun provide{dao}" in
          read("app/src/main/java/com/azu/bookkeeper/core/di/DatabaseModule.kt"))

# ---------- 4. 事务入口约束（架构 N4） ----------
finance_dir = "app/src/main/java/com/azu/bookkeeper/domain/usecase/finance/"
for f in ["AddRefundUseCase.kt", "ReimbursementUseCases.kt", "DebtUseCases.kt",
          "InstallmentUseCases.kt", "BatchBillUseCases.kt"]:
    content = read(finance_dir + f)
    if "billRepository" in content:
        check(f"T-TX-{f} 只用仓储事务入口",
              "addWithBalanceTx" in content or "updateWithBalanceTx" in content
              or "deleteWithBalanceTx" in content,
              "不应出现直接写 accounts 余额的代码")
        check(f"T-TX-{f} 无直写余额",
              "updateBalance(" not in content and "balance_fen" not in content)

# ---------- 5. 路由与入口 ----------
screen = read("app/src/main/java/com/azu/bookkeeper/ui/navigation/Screen.kt")
navhost = read("app/src/main/java/com/azu/bookkeeper/ui/navigation/AppNavHost.kt")
for route in ["Refunds", "Reimbursements", "Debts", "Installments", "BatchAdd",
              "CalendarStats", "AnnualBudget"]:
    check(f"T-NAV-DEF-{route}", f"data object {route}" in screen)
    check(f"T-NAV-REG-{route}", f"Screen.{route}.route" in navhost)

profile = read("app/src/main/java/com/azu/bookkeeper/ui/profile/ProfileScreen.kt")
for route in ["Refunds", "Reimbursements", "Debts", "Installments", "BatchAdd", "AnnualBudget"]:
    check(f"T-ENTRY-{route}", f"Screen.{route}.route" in profile)

# ---------- 6. 新文件存在 ----------
files = [
 "app/src/main/java/com/azu/bookkeeper/ui/finance/RefundsScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/ui/finance/ReimbursementsScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/ui/finance/DebtsScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/ui/finance/InstallmentsScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/ui/finance/BatchAddScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/ui/stats/CalendarStatsScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/ui/budget/AnnualBudgetScreen.kt",
 "app/src/main/java/com/azu/bookkeeper/domain/usecase/stats/StatsEnhanceCalculator.kt",
 "app/src/test/java/com/azu/bookkeeper/domain/usecase/stats/StatsEnhanceCalculatorTest.kt",
 "app/src/test/java/com/azu/bookkeeper/domain/model/FinanceModelsTest.kt",
]
for f in files:
    check(f"T-FILE-{os.path.basename(f)}", os.path.isfile(f))

# ---------- 6.1 新增回归测试与 CI 步骤 ----------
check("T-CI-STATIC 静态测试进入 CI", "python3 scripts/verify_v1_3_8.py" in workflow)
mapper = read("app/src/main/java/com/azu/bookkeeper/data/mapper/EntityMappers.kt")
check("T-IMPORT-01 mapper 导入清理且必需类型可解析",
      "InstallmentPaymentStatus" not in mapper
      and "import com.azu.bookkeeper.domain.model.ImportTaskStatus" in mapper)
check("T-MAP-01 分期流水 domain→entity 映射存在",
      "fun InstallmentPayment.toEntity()" in mapper)
check("T-BATCH-TIME 批量表单提供 billTime",
      "billTime = 0L" in read("app/src/main/java/com/azu/bookkeeper/ui/finance/BatchAddScreen.kt"))
check("T-NO-RECUR-01 债务删除不递归",
      "val r = deleteDebt(id)" in read("app/src/main/java/com/azu/bookkeeper/ui/finance/DebtsScreen.kt"))
check("T-NO-RECUR-02 分期删除不递归",
      "val r = deleteInstallment(id)" in read("app/src/main/java/com/azu/bookkeeper/ui/finance/InstallmentsScreen.kt"))
check("T-NO-RECUR-03 报销删除不递归",
      "val r = update.deleteRecord(id)" in read("app/src/main/java/com/azu/bookkeeper/ui/finance/ReimbursementsScreen.kt"))
check("T-TEST-YOY 同比单测存在",
      "同比按去年同月" in read("app/src/test/java/com/azu/bookkeeper/domain/usecase/stats/StatsEnhanceCalculatorTest.kt"))
check("T-TEST-CARD 提醒配置单测存在",
      "信用卡提醒配置天数范围" in read("app/src/test/java/com/azu/bookkeeper/domain/model/FinanceModelsTest.kt"))

# ---------- 7. 业务逻辑回归 ----------
debt = read("app/src/main/java/com/azu/bookkeeper/domain/model/Debt.kt")
check("T-LOGIC-01 债务剩余不为负", ".coerceAtLeast(0L)" in debt)
check("T-LOGIC-07 债务结清属性与百分比类型正确",
      "val finished: Boolean" in debt and "coerceIn(0L, 100L).toInt()" in debt)
inst = read("app/src/main/java/com/azu/bookkeeper/domain/model/Installment.kt")
check("T-LOGIC-02 分期尾差补齐", "totalFen - perPeriodFen * paidPeriods" in inst)
calc = read("app/src/main/java/com/azu/bookkeeper/domain/usecase/stats/StatsEnhanceCalculator.kt")
check("T-LOGIC-03 统计排除校准", "isAdjustment" in calc)
check("T-LOGIC-04 比例除零保护", "base > 0L" in calc)
worker = read("app/src/main/java/com/azu/bookkeeper/worker/NotificationWorker.kt")
check("T-LOGIC-05 还款日提醒", "checkCardDueReminder" in worker and "<= 3" in worker)
refund = read(finance_dir + "AddRefundUseCase.kt")
check("T-LOGIC-06 退款累计校验", "alreadyRefunded + refund.amountFen > original.amountFen" in refund)

# ---------- 8. 括号配平 ----------
def strip(src):
    out=[];i=0;n=len(src);state=None
    while i<n:
        c=src[i]
        if state is None:
            if src.startswith("//",i):
                j=src.find("\n",i);i=n if j<0 else j;continue
            if src.startswith("/*",i):
                j=src.find("*/",i+2);i=n if j<0 else j+2;continue
            if src.startswith('"""',i):state='"""';i+=3;continue
            if c=='"':state='"';i+=1;continue
            if c=="'":state="'";i+=1;continue
            out.append(c);i+=1
        else:
            if state=='"""' and src.startswith('"""',i):state=None;i+=3;continue
            if state=='"' and c=='\\':i+=2;continue
            if state=='"' and c=='"':state=None;i+=1;continue
            if state=="'" and c=='\\':i+=2;continue
            if state=="'" and c=="'":state=None;i+=1;continue
            i+=1
    return "".join(out)

kt_files = []
for root, dirs, names in os.walk("app/src/main/java"):
    for name in names:
        if name.endswith(".kt"):
            kt_files.append(os.path.join(root, name))
for root, dirs, names in os.walk("app/src/test/java"):
    for name in names:
        if name.endswith(".kt"):
            kt_files.append(os.path.join(root, name))
unbalanced = 0
for f in kt_files:
    t = strip(read(f))
    for a,b in [("{","}"),("(",")"),("[","]")]:
        if t.count(a) != t.count(b):
            print("FAIL T-SYNTAX", f, a, t.count(a), t.count(b))
            failed.append("T-SYNTAX " + f)
            unbalanced += 1
check("T-SYNTAX 全部 Kotlin 文件括号配平", unbalanced == 0, f"{len(kt_files)} files")

# ---------- 输出 ----------
print("\n" + "=" * 50)
print(f"通过 {passed} 项，失败 {len(failed)} 项")
if failed:
    print("失败项：", failed)
    sys.exit(1)
print("v1.3.8 静态一致性测试全部通过")
