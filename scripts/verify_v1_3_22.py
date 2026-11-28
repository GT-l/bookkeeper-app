#!/usr/bin/env python3
"""v1.3.22 静态一致性测试：在 v1.3.21 全量回归基础上，新增微信场景五问题方案检查。"""
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
check("T-VER-01 versionCode 29", "versionCode = 29" in gradle)
check("T-VER-02 versionName 1.3.22", 'versionName = "1.3.22"' in gradle)
check("T-VER-03 Release tag v1.3.22", "tag_name: v1.3.22-debug" in workflow)
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
check("T-A11Y-04 关于页含 v1.3.22 介绍", "v1.3.22" in about)

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
check("T-SIDE-05 XML 不声明 packageNames，且已恢复 canTakeScreenshot",
      "android:packageNames" not in xml and 'android:canTakeScreenshot="true"' in xml
      and 'android:canRetrieveWindowContent="true"' in xml)
check("T-SIDE-06 保留读节点与指纹手势 flag",
      "flagRetrieveInteractiveWindows" in xml and "flagRequestFingerprintGestures" in xml)
check("T-SIDE-07 ADB 一键解锁脚本存在",
      os.path.isfile(adb_script_path) and "appops set" in read(adb_script_path))
check("T-SIDE-08 截屏能力缺失会明确提示重开服务",
      "capabilityMissing" in diag_vm and "canTakeScreenshot 能力重新注册" in diag_vm)

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
check("T-132-08 更新介绍只留最新 + 大版本（<=5 条调用 + 1 定义）", about.count("ChangelogRow(") <= 6)
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
check("T-131-20 版本号 v1.3.22 进入 Release 正文", "v1.3.22-debug" in workflow and "v1.3.22" in about)
check("T-131-21 新增页面识别单测覆盖抖音",
      os.path.isfile("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt")
      and "抖音订单页只有实付也能识别为成功页" in
      read("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt"))
check("T-131-22 统一采集与更新检查单测存在",
      os.path.isfile("app/src/test/java/com/azu/bookkeeper/automation/capture/ScreenTextCollectorTest.kt")
      and os.path.isfile("app/src/test/java/com/azu/bookkeeper/core/update/GithubUpdateCheckerTest.kt"))
check("T-131-23 测试文档已建",
      os.path.isfile("docs/testing/v1.3.14/TEST_CASES.md")
      and os.path.isfile("docs/testing/v1.3.14/TEST_REPORT.md"))

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
      "SettledQuiet" in service and "firstMoneyKey" in service and "currentMoney" in service)
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

# ---------- 1.5 v1.3.9：金额/商户抽取修复 + 列表性能 ----------
analyzer = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/PaymentTextAnalyzer.kt")
home_vm = read("app/src/main/java/com/azu/bookkeeper/ui/home/HomeViewModel.kt")
bill_list = read("app/src/main/java/com/azu/bookkeeper/ui/home/BillList.kt")
common_ui = read("app/src/main/java/com/azu/bookkeeper/ui/common/CommonComponents.kt")

check("T-139-01 金额支持整行纯数字", "AMOUNT_BARE" in analyzer and "v1.3.9" in analyzer)
check("T-139-02 商户支持标签下一行取值",
      "MERCHANT_BARE_LABELS" in analyzer and "lines.getOrNull(index + 1)" in analyzer)
check("T-139-03 收单机构被排除", "收单机构" in analyzer and "财付通" in analyzer)
check("T-139-04 识别器不再猜收单机构",
      "ACQUIRER_LABELS" in recognizer and "extractLabelledMerchant" in recognizer)
check("T-139-05 金额跳过非实付行", "NON_PAYMENT_LINE_HINTS" in recognizer)
check("T-139-06 分组改为构造时预计算",
      "val dayGroups: List<DayGroup> = buildDayGroups(" in home_vm and "fun buildDayGroups(" in home_vm)
check("T-139-07 分组不再用派生 getter", "get() = bills.take(visibleCount)" not in home_vm)
check("T-139-08 列表行使用无阴影卡片",
      "flat = true" in bill_list and "flat: Boolean = false" in common_ui)
check("T-139-09 关于页只列最新与大版本", about.count("ChangelogRow(") <= 6)

# ---------- 1.6 v1.3.10：商户标签残片拦截 ----------
analyzer_test = read("app/src/test/java/com/azu/bookkeeper/automation/PaymentTextAnalyzerTest.kt")
check("T-1310-01 分析器识别标签残片",
      "MERCHANT_LABEL_FRAGMENTS" in analyzer and "\"全称\", \"简称\", \"名称\"" in analyzer)
check("T-1310-02 残片进商户值校验",
      "value.trim('：', ':') in MERCHANT_LABEL_FRAGMENTS" in analyzer)
check("T-1310-03 取下一行时跳过残片继续找",
      "for (offset in 1..3)" in analyzer and "if (value.trim('：', ':') in MERCHANT_LABEL_FRAGMENTS) continue" in analyzer)
check("T-1310-04 识别器同样定义残片",
      "MERCHANT_LABEL_FRAGMENTS" in recognizer and "private val MERCHANT_LABEL_FRAGMENTS = setOf(" in recognizer)
check("T-1310-05 识别器向下扫描最多三行",
      "for (offset in 1..3)" in recognizer and "MERCHANT_LABEL_FRAGMENTS) continue" in recognizer)
check("T-1310-06 残片进噪声词表（兜底猜测也拦）",
      '"全称", "简称", "名称"' in recognizer)
check("T-1310-07 单测覆盖拆分节点",
      "商户与全称被拆成两个节点时跳过残片取真值" in analyzer_test)
check("T-1310-08 单测覆盖只有残片时留空",
      "只有标签残片时宁可留空也不写全称" in analyzer_test)
check("T-1310-09 识别器单测覆盖残片",
      "账单详情补标签时不会把全称当商户" in recognizer_test)

# ---------- 1.7 v1.3.11：英文商户拼接 / 交易时间判重 / 读屏置信度 ----------
service = read("app/src/main/java/com/azu/bookkeeper/automation/service/PaymentAccessibilityService.kt")

check("T-1311-01 英文商户续行拼接",
      "mergeLatinContinuation" in analyzer and "isLatinText" in analyzer)
check("T-1311-02 商户长度上限放宽到 60",
      "MAX_MERCHANT_LENGTH = 60" in analyzer)
check("T-1311-03 账单链接不作为购买内容",
      '".com/"' in analyzer and '"/bill/"' in analyzer)
check("T-1311-04 交易时间抽取方法", "fun transactionTimeKey" in recognizer)
check("T-1311-05 交易时间标签表", "TIME_LABEL_TOKENS" in recognizer and "转账时间" in recognizer)
check("T-1311-06 静默标记带时间指纹",
      "SettledQuiet.Mark" in service and "val timeKey: String" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/SettledQuiet.kt"))
check("T-1311-07 不同交易时间放行",
      "isDifferentBill" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/SettledQuiet.kt")
      and "oldKey == newKey" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/SettledQuiet.kt"))
check("T-1311-08 成功页加成 0.20 / 详情页 0.10",
      "0.20f" in coordinator and "0.10f" in coordinator and "amountOk" in coordinator)
check("T-1311-09 账户兜底",
      "resolvedAccountId" in coordinator and "accounts.firstOrNull()?.accountId" in coordinator)
check("T-1311-10 单测覆盖英文商户拼接",
      "英文商户名被拆成两行时拼接完整" in analyzer_test)
check("T-1311-11 单测覆盖交易时间",
      "交易时间优先取带标签的行" in recognizer_test and "苹果账单详情能抽到支付时间" in recognizer_test)
check("T-1311-12 单测覆盖收款成功页", "收款成功页识别为收入" in recognizer_test)

# ---------- 1.8 v1.3.12：截屏通道恢复 / 截图 OCR 兜底 / 授权引导 ----------
a11y_optin = read("app/src/main/java/com/azu/bookkeeper/core/A11yShotOptIn.kt")
diagnostics = read("app/src/main/java/com/azu/bookkeeper/ui/settings/CaptureDiagnosticsViewModel.kt")
lightweight = read("app/src/main/java/com/azu/bookkeeper/ui/quick/LightweightCapture.kt")
shake = read("app/src/main/java/com/azu/bookkeeper/ui/quick/ShakeQuickAddService.kt")

check("T-1312-01 无障碍截屏能力已恢复",
      'android:canTakeScreenshot="true"' in xml)
check("T-1312-02 仍不声明 packageNames",
      "android:packageNames" not in xml)
check("T-1312-03 开关默认开启",
      "getBoolean(KEY_ENABLED, true)" in a11y_optin and "getOrDefault(true)" in a11y_optin)
check("T-1312-04 服务依赖可拿到通道与 OCR",
      "fun channels(): CaptureChannels" in service and "fun ocrIpc(): OcrIpcClient" in service)
check("T-1312-05 节点为空走截图 OCR",
      "scheduleScreenshotFallback" in service and "节点为空，转截图 OCR" in service)
check("T-1312-06 截图兜底 15 秒节流",
      "SHOT_FALLBACK_INTERVAL_MS = 15_000L" in service and "lastShotFallbackAt" in service)
check("T-1312-07 截图 OCR 后仍走页面识别", 
      "PaymentScreenRecognizer.classify(packageName, className, ocrText)" in service)
check("T-1312-08 摇一摇无通道引导授权",
      "shouldGuideToAuth" in shake and "requestCaptureAuthorization(capture)" in shake)
check("T-1312-09 授权引导 60 秒节流",
      "AUTH_GUIDE_INTERVAL_MS = 60_000L" in shake and "lastAuthGuideAt" in shake)
check("T-1312-10 轻量识屏暴露通道状态",
      "fun captureChannelReady()" in lightweight and "fun projectionSessionActive()" in lightweight)
check("T-1312-11 诊断页不再当成预期行为",
      "已按最小权限关闭无障碍截屏" not in diagnostics and "canTakeScreenshot 能力重新注册" in diagnostics)

# ---------- 1.9 v1.3.13：事件到达统计 / 存储排查 / 日志默认开启 ----------
event_stats = read("app/src/main/java/com/azu/bookkeeper/core/AccessibilityEventStats.kt")
storage_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/StorageAuditViewModel.kt")
capture_log = read("app/src/main/java/com/azu/bookkeeper/core/CaptureLog.kt")
main_activity = read("app/src/main/java/com/azu/bookkeeper/MainActivity.kt")

check("T-1313-01 事件统计在任何过滤之前计数",
      "AccessibilityEventStats.onEvent" in service and
      "val isPayment = PaymentScreenRecognizer.isPaymentPackage(rawPackage)" in service)
check("T-1313-02 统计含支付应用与最后包名",
      "paymentTotal" in event_stats and "lastPackage" in event_stats)
check("T-1313-03 自检页新增事件到达检查",
      "eventArrivalCheck" in diag_vm and "无障碍事件到达" in diag_vm)
check("T-1313-04 存储排查新增安装包分类",
      'APK("安装包残留")' in storage_vm)
check("T-1313-05 扫描 updates 与 incoming-apk",
      '"updates"' in storage_vm and '"incoming-apk"' in storage_vm)
check("T-1313-06 识屏日志单独标注",
      "capture_log.txt" in storage_vm and "自检页「复制 log」的来源" in storage_vm)
check("T-1313-07 识屏日志默认开启",
      "getBoolean(KEY_ENABLED, true)" in capture_log and "getOrDefault(true)" in capture_log)
check("T-1313-08 回到前台即体检无障碍",
      "A11yWatchdogService.checkNow(this@MainActivity)" in main_activity)

# ---------- 1.10 v1.3.14：抖音按页面时间判重 ----------
check("T-1314-01 协调器用页面时间覆盖账单时间",
      "transactionTimeKey(event.rawText)" in coordinator and "fields = fields.copy(billTime = screenTimeMillis)" in coordinator)
check("T-1314-02 页面时间解析走 parseFlexible",
      "DateTimeUtils.parseFlexible(screenTime)" in coordinator)
check("T-1314-03 抖音静默窗口 60 秒",
      "PaymentScene.DOUYIN_ORDER" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/SettledQuiet.kt")
      and "60_000L" in read("app/src/main/java/com/azu/bookkeeper/automation/dedup/SettledQuiet.kt"))
check("T-1314-04 下单时间在时间标签表",
      "下单时间" in recognizer)
check("T-1314-05 单测覆盖抖音订单时间",
      "抖音订单页能抽到下单时间" in recognizer_test and "抖音订单页缺时间时交易时间为空" in recognizer_test)

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
check("T-CI-STATIC 静态测试进入 CI", "python3 scripts/verify_v1_3_22.py" in workflow)
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

# ---------- 1.20 v1.3.15：下载断点续传 / 进度可读 / 通道合并 / 资源闸门 ----------
# 用户反馈「下载变慢了」的四个真因：88MB 单线程无续传、进度只有 1% 粒度、
# API 通道多一跳、识屏截图 OCR 与下载抢 CPU。以下逐条钉住实现。
checker = read("app/src/main/java/com/azu/bookkeeper/core/update/GithubUpdateChecker.kt")
budget_path = "app/src/main/java/com/azu/bookkeeper/core/CaptureBudget.kt"
budget = read(budget_path) if os.path.isfile(budget_path) else ""
about_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AboutViewModel.kt")
update_test = read("app/src/test/java/com/azu/bookkeeper/core/update/GithubUpdateCheckerTest.kt")

# B. 断点续传
check("T-DL-01 续传发 Range 头", 'setRequestProperty("Range", "bytes=$resumeFrom-")' in checker)
check("T-DL-02 206 判定续写", "val append = code == 206 && resumeFrom > 0L" in checker)
check("T-DL-03 追加模式打开 .part", "FileOutputStream(part, from > 0L)" in checker)
check("T-DL-04 断流保留断点", "keepPart = done > from" in checker)
check("T-DL-05 只有确定性失败才删断点", "if (!completed && !keepPart)" in checker)
check("T-DL-06 416 视为断点已完整", "416 -> {" in checker and "finishApk(part, target, part.length(), onProgress)" in checker)
check("T-DL-07 落盘后 fsync", "out.fd.sync()" in checker)
check("T-DL-08 续传大小校验", "done < expectedSize" in checker)
check("T-DL-09 已下完的文件直接复用", "reused = true" in checker)
check("T-DL-10 脏断点（比远端大）丢弃", "resumeFrom > update.sizeBytes" in checker)

# C. 进度可读
check("T-DL-11 进度含字节与速率", "data class DownloadProgress(" in checker
      and "bytesPerSecond" in checker and "etaSeconds" in checker)
check("T-DL-12 进度 200ms 节流", "PROGRESS_INTERVAL_MS = 200L" in checker
      and "private class ProgressEmitter" in checker)
check("T-DL-13 速率四次采样平滑", "(speed * 3L + instant) / 4L" in checker)
check("T-DL-14 128KB 读取缓冲", "BUFFER_BYTES = 128 * 1024" in checker)
check("T-DL-15 速率/剩余时间格式化", "fun formatSpeed(" in checker and "fun formatEta(" in checker)

# D. 通道合并：直链优先，API 只兜底
check("T-DL-16 直链作为主地址", "downloadUrl = asset.browserDownloadUrl" in checker)
check("T-DL-17 API 降为兜底", "fallbackUrl = apiDownloadUrl" in checker)
check("T-DL-18 browserUrl 语义反转", "get() = downloadUrl.ifBlank { fallbackUrl }" in checker)

# E. 下载期让出 CPU
check("T-DL-19 资源闸门文件存在", "object CaptureBudget" in budget)
check("T-DL-20 闸门计数不越界", "if (heavyTasks.decrementAndGet() < 0) heavyTasks.set(0)" in budget)
check("T-DL-21 下载包一层闸门", "CaptureBudget.runHeavy {" in about_vm)
check("T-DL-22 截图兜底检查闸门", "if (CaptureBudget.isBusy)" in service)
check("T-DL-23 协程内二次检查", service.count("CaptureBudget.isBusy") >= 2)
check("T-DL-24 下载结果写日志", 'CaptureLog.forceInfo(' in about_vm and '"UPDATE"' in about_vm)

# UI 与诊断
check("T-DL-25 关于页显示进度明细", "state.progressDetail" in about)
check("T-DL-26 状态含 progressDetail", "val progressDetail: String = \"\"" in about_vm)
check("T-DL-27 单测覆盖速率/剩余时间", "formatSpeed" in update_test and "formatEta" in update_test)
check("T-DL-28 单测覆盖进度明细", "detailText()" in update_test)
check("T-DL-29 Release 正文含版本说明", "本版按工单逐条处置" in workflow)
# 进度百分比必须是 0..100（曾写成千分位，10% 就显示 100%）
check("T-DL-30 百分比抽成可单测函数", "fun percentOf(bytes: Long, total: Long): Int" in checker
      and "GithubUpdateChecker.percentOf(bytes, total)" in checker)
check("T-DL-31 单测钉住百分比", "percentOf" in update_test)
# 两次 run 抢同一个 tag 会互相覆盖资产
check("T-DL-32 CI 并发取消旧构建", "concurrency:" in workflow and "cancel-in-progress: true" in workflow)

# ---------- 1.21 v1.3.16：截图帧体检 / OCR 错误可见 / 空白帧引导 ----------
# 真机证据（realme RMX3888 / Android 37 15:56:13）：
# takeScreenshot 成功 1264x2780 → OCR「0 字 blocks=0」→ 报 EXTRACT_EMPTY。
# 结论：系统给的是空白帧，旧判定只算平均亮度，全白/全灰漏过，空图进了 OCR。
frame = read("app/src/main/java/com/azu/bookkeeper/automation/capture/FrameSanity.kt")
channels = read("app/src/main/java/com/azu/bookkeeper/automation/capture/CaptureChannels.kt")
ocr_result = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/OcrResult.kt")
ocr_engine = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/OcrEngine.kt")
ocr_ipc = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/OcrIpc.kt")
light = read("app/src/main/java/com/azu/bookkeeper/ui/quick/LightweightCapture.kt")
shake = read("app/src/main/java/com/azu/bookkeeper/ui/quick/ShakeQuickAddService.kt")
frame_test = read("app/src/test/java/com/azu/bookkeeper/automation/capture/FrameSanityTest.kt")

check("T-FRAME-01 帧体检能力存在", "object FrameSanity" in frame)
check("T-FRAME-02 单色阈值可审", "const val UNIFORM_SPAN = 12" in frame)
check("T-FRAME-03 判定抽成纯函数（可单测）",
      "fun isUniform(minLuma: Int, maxLuma: Int, samples: Int): Boolean" in frame)
check("T-FRAME-04 统计含亮暗跨度", "val maxLuma: Int" in frame and "val minLuma: Int" in frame)
check("T-FRAME-05 统计可读化入日志", "fun describe(): String" in frame)
check("T-FRAME-06 无障碍截屏走帧体检", "FrameSanity.analyze(it)" in channels)
check("T-FRAME-07 空白帧不再进 OCR（不再直接 return bitmap）",
      "无障碍截屏：空白帧" in channels and "无障碍截屏返回空白帧" in channels)
check("T-FRAME-08 空白帧让给媒体投影通道", "accessibilityReason = CaptureFailReason.BLACK_FRAME" in channels)
check("T-FRAME-09 旧平均亮度判定已删除", "isBlackFrame" not in channels)
check("T-FRAME-10 媒体投影也做帧体检", "媒体投影帧体检" in channels)

check("T-OCRERR-01 结果类型带错误原因", "val error: String? = null" in ocr_result)
check("T-OCRERR-02 引擎异常不再吞成 0 字", "error = \"${e.javaClass.simpleName}" in ocr_engine)
check("T-OCRERR-03 IPC 载荷带错误原因", "val error: String? = null" in ocr_ipc)
check("T-OCRERR-04 worker 失败也回传原因", "ocr worker failed" in ocr_ipc and "OcrIpcPayload(\n                        error =" in ocr_ipc)
check("T-OCRERR-05 客户端区分异常与空文本", "识别异常 0 字 blocks=" in ocr_ipc)
check("T-OCRERR-06 上层单独报引擎异常", "OCR 引擎异常" in light)
check("T-OCRERR-07 0 字结果带帧体检", "0 字（帧体检" in light)

check("T-GUIDE-01 空白帧也触发录屏授权引导", "reason == CaptureFailReason.BLACK_FRAME" in shake)
check("T-GUIDE-02 已有录屏会话不反复弹", "if (capture.projectionSessionActive()) return false" in shake)
check("T-FRAME-11 单测覆盖单色判定", "FrameSanity.isUniform" in frame_test)

# ---------- 1.22 v1.3.17：ML Kit 显式初始化 + 主进程兜底 ----------
# 真机证据（realme RMX3888 / Android 37 16:08:07）：
#   WARN[OCR] 识别异常 0 字 blocks=0 原因=IllegalStateException: MlKitContext has not been initialized
# 结论：ML Kit 的 MlKitInitProvider 在 :ocr 进程没生效，getClient() 直接抛异常；
# 旧代码把异常吞成「0 字」，于是永远显示「截图成功但没找到金额」。
versions = read("gradle/libs.versions.toml")
check("T-MLK-01 声明 mlkit common 依赖", "mlkit-common = { module = \"com.google.mlkit:common\"" in versions)
check("T-MLK-02 app 引入 common",
      "implementation(libs.mlkit.common)" in gradle)
check("T-MLK-03 引擎显式初始化", "com.google.mlkit.common.MlKit.initialize(context)" in ocr_engine)
_lazy = ocr_engine[ocr_engine.index("private val recognizer by lazy"):ocr_engine.index("private fun initializeMlKit")]
check("T-MLK-04 初始化在取识别器之前",
      "initializeMlKit()" in _lazy and
      _lazy.index("initializeMlKit()") < _lazy.index("TextRecognition.getClient"))
check("T-MLK-05 初始化失败留痕", "MlKit 显式初始化失败" in ocr_engine)
check("T-MLK-06 客户端注入本地引擎", "private val localEngine: OcrEngine" in ocr_ipc)
check("T-MLK-07 已判定不可用则短路", "@Volatile\n    private var ipcUnusable = false" in ocr_ipc
      and "if (ipcUnusable) return recognizeLocally(bitmap" in ocr_ipc)
check("T-MLK-08 识别未初始化失败原因", "text.contains(\"MlKitContext\")" in ocr_ipc)
check("T-MLK-09 命中后改主进程识别", "recognizeLocally(bitmap, \"跨进程未初始化\")" in ocr_ipc)
check("T-MLK-10 本地识别结果结构一致", "OcrIpcPayload(r.rawText, r.confidence, r.blockCount, r.error)" in ocr_ipc)

# ---------- 1.23 v1.3.18：采集判据从「稀疏」改成「交易证据」 ----------
# 用户场景：微信付款记录页根节点只返回「返回 / 支付 / 完成」几行，
# 既不稀疏也没有金额 → 旧逻辑既不读其它窗口也不退截图 OCR，什么都不发生。
recognizer_kt = read("app/src/main/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizer.kt")
recognizer_test = read("app/src/test/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizerTest.kt")
collector_kt = read("app/src/main/java/com/azu/bookkeeper/automation/capture/ScreenTextCollector.kt")

check("T-PICK-01 交易证据判定存在", "fun hasTransactionSignal(text: String): Boolean" in recognizer_kt)
check("T-PICK-02 金额与凭据都算证据",
      "text.contains('¥') || text.contains('￥')" in recognizer_kt
      and "RECEIPT_CONTEXT.any { text.contains(it) }" in recognizer_kt)
check("T-PICK-03 自动识屏不再只看稀疏",
      "if (!PaymentScreenRecognizer.hasTransactionSignal(ScreenTextCollector.merge(parts)))" in service)
check("T-PICK-04 自动识屏读窗口数 3", "for (w in windows.take(3))" in service)
check("T-PICK-05 摇一摇快照同口径",
      "if (!PaymentScreenRecognizer.hasTransactionSignal(parts.first()))" in service)
check("T-PICK-06 截图兜底覆盖「有文本无证据」",
      "(!hasSignal && looksLikePaymentPage)" in service)
check("T-PICK-07 页面词表只用于触发 OCR", "private val PAGE_HINTS = listOf(" in service)
check("T-PICK-08 稀疏判定仍保留（诊断用）", "fun isSparse(" in collector_kt)
check("T-PICK-09 单测覆盖无证据文本", "只有无关文本时没有交易证据" in recognizer_test)
check("T-PICK-10 单测覆盖金额/凭据", "有金额就有交易证据" in recognizer_test
      and "有凭据字段也算交易证据" in recognizer_test)

# ---------- 1.24 v1.3.19：收支方向 + 金额合理性 ----------
# 真机事故：微信收款被记成支出；实际 21.60 的一笔被记成 111845.00。
extractor_kt = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/FieldExtractor.kt")
analyzer_kt = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/PaymentTextAnalyzer.kt")
auto_kt = read("app/src/main/java/com/azu/bookkeeper/domain/usecase/bill/AutoRecordUseCase.kt")
light_kt = read("app/src/main/java/com/azu/bookkeeper/ui/quick/LightweightCapture.kt")
shake_kt = read("app/src/main/java/com/azu/bookkeeper/ui/quick/ShakeQuickAddService.kt")
extractor_test = read("app/src/test/java/com/azu/bookkeeper/automation/ocr/FieldExtractorTest.kt")

check("T-DIR-01 收入判定存在", "fun looksLikeIncome(text: String): Boolean" in recognizer_kt)
check("T-DIR-02 收款词表", "private val RECEIVE_HINTS" in recognizer_kt)
check("T-DIR-03 支出优先", "EXPENSE_STRONG_HINTS.any { text.contains(it) }" in recognizer_kt)
check("T-DIR-04 识屏结果带账单类型", "val billType: com.azu.bookkeeper.domain.model.BillType" in light_kt)
check("T-DIR-05 识别成功按文本判方向", "PaymentScreenRecognizer.looksLikeIncome(rawText)" in light_kt)
check("T-DIR-06 自动记账接受方向参数", "billType: BillType = BillType.EXPENSE" in auto_kt)
check("T-DIR-07 不再写死 EXPENSE", "billType = BillType.EXPENSE," not in auto_kt and "billType = billType," in auto_kt)
check("T-DIR-08 调用方传方向", "billType = outcome.billType" in shake_kt)
check("T-DIR-09 标题跟方向", 'title = "识别到一笔${outcome.billType.label}"' in shake_kt)
check("T-DIR-10 单测覆盖收入判定", "收款到账页面判为收入" in recognizer_test)

check("T-AMT-01 金额逐候选校验", "for (raw in candidates)" in extractor_kt)
check("T-AMT-02 7 位以上整数不当金额", "if (intDigits >= 7) return false" in extractor_kt)
check("T-AMT-03 单笔十万上限", "MAX_SINGLE_BILL_FEN = 10_000_000L" in extractor_kt)
check("T-AMT-04 序列号行排除", "SERIAL_HINTS.any { line.contains(it) }" in extractor_kt)
check("T-AMT-05 分析器同口径上限", "if (fen >= 10_000_000L) return false" in analyzer_kt)
check("T-AMT-06 单测覆盖累计算法", "超过十万元的数字不当单笔金额" in extractor_test)
check("T-AMT-07 日志带方向与原文", "type=${billType.label}" in light_kt and "原文=${ScreenTextCollector.preview(rawText)}" in light_kt)
check("T-AMT-08 失败日志带原文", "原文=${ScreenTextCollector.preview(rawText)}" in light_kt)

# ---------- 1.25 v1.3.20：触发硬闸 / 金额优先级 / 旧版本清理 ----------
storage_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/StorageAuditViewModel.kt")
storage_ui = read("app/src/main/java/com/azu/bookkeeper/ui/settings/StorageAuditScreen.kt")

check("T-GUARD-01 唯一订单号判定", "fun hasOrderId(text: String): Boolean" in recognizer_kt)
check("T-GUARD-02 抖音类名分支要单号", "val hasResultClass = hasOrderId &&" in recognizer_kt)
check("T-GUARD-03 抖音文案分支要单号", "dHit != null && hasOrderId && hasOrderContext" in recognizer_kt)
check("T-GUARD-04 聊天页结构特征排除", "private val CHAT_UI_HINTS" in recognizer_kt
      and "if (CHAT_UI_HINTS.any { compact.contains(it) })" in recognizer_kt)
check("T-GUARD-05 中间态扩充", "\"待支付\", \"待付款\", \"支付中\", \"处理中\", \"待确认\"" in recognizer_kt)
check("T-GUARD-06 成功词不被中间态误伤", "(COMMON_SUCCESS + WECHAT_SUCCESS).none { compact.contains(it) }" in recognizer_kt)
check("T-GUARD-07 单测覆盖列表页禁止", "抖音订单列表页只有商家和金额时不判成功" in recognizer_test)
check("T-GUARD-08 单测覆盖聊天页排除", "微信聊天页结构特征不参与判定" in recognizer_test)

check("T-PRICE-01 实付合计优先正则", "private val amountFinal" in extractor_kt)
check("T-PRICE-02 候选顺序最前", "amountFinal.findAll(text)," in extractor_kt)
check("T-PRICE-03 排除小计原价优惠", "\"小计\", \"原价\", \"优惠\", \"运费\"" in extractor_kt)
check("T-PRICE-04 单测覆盖优先级", "实付金额优先于小计与优惠" in extractor_test)

check("T-CLEAN-01 一键选中入口", "fun selectObsolete()" in storage_vm)
check("T-CLEAN-02 备份永不自动选", "Kind.BACKUP -> false" in storage_vm)
check("T-CLEAN-03 UI 按钮", "一键选中旧版本残留（不含备份）" in storage_ui)
check("T-CLEAN-04 UI 解释 prefs 残留", "覆盖安装不会清除应用私有数据" in storage_ui)

# ---------- 1.26 v1.3.21：规则一/二 + 冻结校验 ----------
import hashlib
resolver = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/ChannelMerchantResolver.kt")
anomaly = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/GoodsOcrAnomaly.kt")
analyzer_kt = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/PaymentTextAnalyzer.kt")
resolver_test = read("app/src/test/java/com/azu/bookkeeper/automation/ocr/ChannelMerchantResolverTest.kt")
anomaly_test = read("app/src/test/java/com/azu/bookkeeper/automation/ocr/GoodsOcrAnomalyTest.kt")
guard_test = read("app/src/test/java/com/azu/bookkeeper/automation/capture/NonFinalPageGuardTest.kt")
test_cases = read("docs/testing/v1.3.21/TEST_CASES.md")

# 规则一：财付通向上追溯真实商户（受 [规则一] 约束）
check("T-R1-01 回溯入口存在", "fun backtrack(lines: List<String>): String?" in resolver)
check("T-R1-02 注释标注规则编号", "受 [规则一] 约束" in resolver)
check("T-R1-03 只看财付通独占行", "CHANNEL_TOKENS.contains(it)" in resolver and 'setOf("财付通"' in resolver)
check("T-R1-04 回溯最多3行", "LOOK_BACK_LINES = 3" in resolver)
check("T-R1-05 小程序优先", "it.contains(MINI_PROGRAM_TOKEN)" in resolver)
check("T-R1-06 排除通道名", '"微信支付"' in resolver and '"支付服务"' in resolver)
check("T-R1-07 排除银行", 'BANK_TOKEN = "银行"' in resolver)
check("T-R1-08 排除金额行", "AMOUNT_LINE.matches(line)" in resolver)
check("T-R1-09 排除时间行", "DATE_LINE.containsMatchIn(line)" in resolver)
check("T-R1-10 排除状态行", "STATUS_TOKENS.any" in resolver)
check("T-R1-11 未完成态不回填", "PENDING_STATUS.any" in resolver)
check("T-R1-12 接入分析器最后兜底", "ChannelMerchantResolver.backtrack(lines).orEmpty()" in analyzer_kt)
check("T-R1-13 样本1 小程序名", "喜茶点单小程序" in resolver_test)
check("T-R1-14 样本2 微信支付+日期不回填", "2024-01-01" in resolver_test and "微信支付" in resolver_test)

# 规则二：商品名 OCR 误识别提醒（受 [规则二] 约束）
check("T-R2-01 检测器入口存在", "fun detect(goods: String): Boolean" in anomaly)
check("T-R2-02 注释标注规则编号", "受 [规则二] 约束" in anomaly)
check("T-R2-03 固定提醒文案", "⚠️ 识别失败：商品名可能被误识别，建议人工核对" in anomaly)
check("T-R2-04 条件A 音译高频字", "TRANSLIT_CHARS" in anomaly and "conditionA(value)" in anomaly)
check("T-R2-05 条件C 孤立字母", "isolated < 2" in anomaly and "conditionC(value)" in anomaly)
check("T-R2-06 条件B 已跳过（无样本不猜测）", "跳过" in anomaly and "条件 B" in anomaly)
check("T-R2-07 纯英文白名单", "value.none { it.code in CJK_START..CJK_END }" in anomaly)
check("T-R2-08 只置标志不阻断", "goodsWarning = !paymentRejected" in analyzer_kt)
check("T-R2-09 未完成态不弹窗", "GOODS_WARNING_PENDING" in analyzer_kt and "!pendingPage" in analyzer_kt)
check("T-R2-10 样本1 艾尔普兹无线", "艾尔普兹无线" in anomaly_test)
check("T-R2-11 样本2 苹果AirPods Pro", "苹果AirPods Pro" in anomaly_test)
check("T-R2-12 样本3 A艾尔P兹", "A艾尔P兹" in anomaly_test)
check("T-R2-13 样本4 苹果无线耳机", "苹果无线耳机" in anomaly_test)
check("T-R2-14 上层输出固定提醒", "GoodsOcrAnomaly.WARNING" in read("app/src/main/java/com/azu/bookkeeper/ui/quick/LightweightCapture.kt"))
check("T-R2-15 原始OCR各行写日志", "原文各行=${rawText.lines().joinToString" in read("app/src/main/java/com/azu/bookkeeper/ui/quick/LightweightCapture.kt"))

# 交付要求：跳过原因 / 自测 / 冻结
check("T-SKIP-01 条件B跳过原因", "条件 B（纯拼音伪装）" in test_cases and "跳过原因" in test_cases)
check("T-SKIP-02 规则三跳过原因", "退款生成收入冲正 —— 跳过原因" in test_cases)
check("T-SKIP-03 规则四跳过原因", "群收款单笔详情 —— 跳过原因" in test_cases)
check("T-SELF-01 待支付页自测", "待支付页判为ENTRY" in guard_test)
check("T-SELF-02 选择页自测", "选择支付方式页判为ENTRY" in guard_test)
check("T-SELF-03 发起页自测", "收银台发起页判为ENTRY" in guard_test)

# T-FROZEN-01 规则五：转账/红包判定文件一字未改（SHA-256 钉住）
_freeze_path = "app/src/main/java/com/azu/bookkeeper/automation/capture/PaymentScreenRecognizer.kt"
_freeze_sha = hashlib.sha256(read(_freeze_path).encode("utf-8")).hexdigest()
check("T-FROZEN-01 转账红包判定文件未改",
      _freeze_sha == "6f39f8c5d23d9b19b8ff220eab38bfeadb627dc100a1b741ae2b37edff437eb5",
      _freeze_sha[:12])

# ---------- 1.27 v1.3.22：微信场景五问题方案 ----------
settled = read("app/src/main/java/com/azu/bookkeeper/automation/dedup/SettledQuiet.kt")
pruner = read("app/src/main/java/com/azu/bookkeeper/automation/dedup/CaptureEventPruner.kt")
capture_dao = read("app/src/main/java/com/azu/bookkeeper/data/local/db/dao/CaptureDao.kt")
settings_ds = read("app/src/main/java/com/azu/bookkeeper/data/local/datastore/AutomationSettingsDataStore.kt")
settings_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AutomationSettingsViewModel.kt")
settings_ui = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AutomationSettingsScreen.kt")
plan = read("docs/testing/v1.3.22/PLAN.md")
quiet_test = read("app/src/test/java/com/azu/bookkeeper/automation/dedup/SettledQuietTest.kt")
prune_test = read("app/src/test/java/com/azu/bookkeeper/automation/dedup/CaptureEventPrunerTest.kt")

# 问题一：诊断标记（不改触发）
check("T-W1-01 待完善场景标记", "待完善场景" in service)
check("T-W1-02 标记带原文预览", "hasReceipt=$looksReceipt" in service)
check("T-W1-03 方案评估文档", "问题 1：微信场景无法触发识屏" in plan)

# 问题二：可选解析轨迹
check("T-W2-01 解析开关存储", "PARSE_TRACE_ENABLED" in settings_ds)
check("T-W2-02 开关默认关", 'it[Keys.PARSE_TRACE_ENABLED] ?: false' in settings_ds)
check("T-W2-03 设置页开关", "解析调试日志" in settings_ui)
check("T-W2-04 ViewModel 状态", "parseTraceEnabled" in settings_vm)
check("T-W2-05 分析器轨迹开关", "fun analyze(rawText: String, includeTrace: Boolean = false)" in analyzer_kt)
check("T-W2-06 轨迹含字段候选", "金额候选=" in analyzer_kt and "商户候选=" in analyzer_kt)
check("T-W2-07 轨迹不改判定", "PaymentAnalysis(" in analyzer_kt and "trace = trace" in analyzer_kt)
check("T-W2-08 单测覆盖轨迹", "开启后输出字段候选与最终决策" in read("app/src/test/java/com/azu/bookkeeper/automation/PaymentTextAnalyzerTest.kt"))

# 问题三：静默期纯函数
check("T-W3-01 纯函数存在", "object SettledQuiet" in settled)
check("T-W3-02 时间必须可解析", "DateTimeUtils.parseFlexible(oldKey) ?: return false" in settled)
check("T-W3-03 转账红包60秒", "PaymentScene.WECHAT_TRANSFER_IN" in settled and "60_000L" in settled)
check("T-W3-04 服务接入纯函数", "SettledQuiet.shouldBlock(" in service)
check("T-W3-05 单测覆盖时间抖动", "时间串抖动但无法解析时不放行" in quiet_test)
check("T-W3-06 单测覆盖重复转账", "同一笔转账60秒内重复扫描被拦截" in quiet_test)

# 问题四：黑名单 + 多通道回溯
check("T-W4-01 黑名单补齐通道", all(x in analyzer_kt for x in ["网联", "快钱", "易宝", "拉卡拉", "微信支付"]))
check("T-W4-02 回溯支持多通道", "CHANNEL_TOKENS = setOf(" in resolver)
check("T-W4-03 单测覆盖多通道", "网联银联快钱易宝拉卡拉通道也能向上追溯" in resolver_test)
check("T-W4-04 冻结文件未改（再确认）", _freeze_sha == "6f39f8c5d23d9b19b8ff220eab38bfeadb627dc100a1b741ae2b37edff437eb5")

# 问题五：事件表清理
check("T-W5-01 清理策略纯函数", "object CaptureEventPruner" in pruner)
check("T-W5-02 终态专属删除", "status IN (2, 3, 4)" in capture_dao)
check("T-W5-03 原文截断", "boundedRawText(event.rawText)" in coordinator)
check("T-W5-04 清理入口接入", "runCatching { pruneEvents() }" in coordinator)
check("T-W5-05 待校对不删（DAO 注释）", "待处理(0) / 待校对(1) 事件绝不能删" in capture_dao)
check("T-W5-06 单测覆盖截断", "原文截断到2000字" in prune_test)
check("T-W5-07 单测覆盖节流", "24小时内不重复清理" in prune_test)

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
print("v1.3.22 静态一致性测试全部通过")
