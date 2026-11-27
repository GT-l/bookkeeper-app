#!/usr/bin/env python3
"""v1.3.0 静态一致性测试：迁移 SQL、实体/DAO/路由一致性、事务入口约束、无障碍诊断与固定签名。"""
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
check("T-VER-01 versionCode 7", "versionCode = 7" in gradle)
check("T-VER-02 versionName 1.3.0", 'versionName = "1.3.0"' in gradle)
check("T-VER-03 Release tag v1.3.0", "tag_name: v1.3.0-debug" in workflow)
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
check("T-A11Y-04 关于页含 v1.3.0 介绍", "v1.3.0" in about)

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
check("T-REC-08 节点深度放宽且 text/desc 都收",
      "MAX_DEPTH = 24" in a11y_parser and "contentDescription" in a11y_parser)
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
check("T-OVL-04 校对链优先底部卡片", "preferOverlay" in verifier)

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
check("T-CI-STATIC 静态测试进入 CI", "python3 scripts/verify_v1_3_0.py" in workflow)
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
print("v1.3.0 静态一致性测试全部通过")
