#!/usr/bin/env python3
"""v1.2.0 静态一致性测试：迁移 SQL、实体/DAO/路由一致性、事务入口约束。"""
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
check("T-VER-01 versionCode 3", "versionCode = 3" in gradle)
check("T-VER-02 versionName 1.2.0", 'versionName = "1.2.0"' in gradle)
check("T-VER-03 Release tag v1.2.0", "tag_name: v1.2.0-debug" in workflow)
check("T-VER-04 DATABASE_VERSION 2", "DATABASE_VERSION = 2" in constants)

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
check("T-CI-STATIC 静态测试进入 CI", "python3 scripts/verify_v1_2_0.py" in workflow)
mapper = read("app/src/main/java/com/azu/bookkeeper/data/mapper/EntityMappers.kt")
check("T-IMPORT-01 无幽灵导入 InstallmentPaymentStatus",
      "InstallmentPaymentStatus" not in mapper)
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
check("T-LOGIC-07 债务结清属性供 UI 使用", "val finished: Boolean" in debt)
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
print("v1.2.0 静态一致性测试全部通过")
