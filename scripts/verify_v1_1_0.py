#!/usr/bin/env python3
"""v1.1.0 静态一致性测试。

为什么需要它：本机没有 Android SDK / JDK，无法本地跑 Gradle；而 CI 的
`Run unit tests` 步骤带 continue-on-error，表面的绿色不代表单测全过。
本脚本用纯 Python 检查「跨文件约定」这类编译器抓不到、但会导致线上行为错误的问题：
版本号四处一致、新路由三处注册、锁定项规则、转场时长上限、残留旧标记、源文件结构。

用法：python3 scripts/verify_v1_1_0.py       # 仓库根目录执行
退出码 0 = 全部通过，1 = 有失败项。
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def read(rel):
    p = ROOT / rel
    return p.read_text(encoding="utf-8") if p.exists() else ""


# ---------- 1. 版本号四处一致 ----------
gradle = read("app/build.gradle.kts")
workflow = read(".github/workflows/android.yml")
readme = read("README.md")
about = read("app/src/main/java/com/azu/bookkeeper/ui/settings/AboutScreen.kt")

check("T-VER-01 versionCode = 2", "versionCode = 2" in gradle)
check("T-VER-02 versionName = 1.1.0", 'versionName = "1.1.0"' in gradle)
check("T-VER-03 Release 标签 v1.1.0-debug", "tag_name: v1.1.0-debug" in workflow)
check("T-VER-04 Release 名称 v1.1.0", "少花点儿 v1.1.0 (Debug APK)" in workflow)
check("T-VER-05 README 标注 v1.1.0", "v1.1.0" in readme)
check("T-VER-06 关于页标注 v1.1.0", "v1.1.0" in about)
check(
    "T-VER-07 无残留 v1.0.0-debug 发布标签",
    "tag_name: v1.0.0-debug" not in workflow,
)

# ---------- 2. 新路由三处注册 ----------
screen = read("app/src/main/java/com/azu/bookkeeper/ui/navigation/Screen.kt")
navhost = read("app/src/main/java/com/azu/bookkeeper/ui/navigation/AppNavHost.kt")
entry = read("app/src/main/java/com/azu/bookkeeper/ui/navigation/AppEntryRouter.kt")

for route_name in ("Profile", "StorageAudit", "About"):
    check(
        f"T-ROUTE-{route_name}-01 Screen 定义",
        f"data object {route_name} : Screen" in screen,
    )
    check(
        f"T-ROUTE-{route_name}-02 NavHost 注册",
        f"composable(\n                route = Screen.{route_name}.route" in navhost
        or f"composable(Screen.{route_name}.route)" in navhost,
    )

check("T-ROUTE-ENTRY-01 Profile 在外部入口白名单", "Screen.Profile.route" in entry)
check(
    "T-ROUTE-TAB-01 底部第 4 个 tab 指向 Profile",
    'BottomTab(Screen.Profile.route, "我的", R.drawable.ic_tab_settings)' in navhost,
)
check(
    "T-ROUTE-TAB-02 底部栏只在四个主 tab 显示",
    "val showBottomBar = bottomTabs.any { it.route == currentRoute }" in navhost,
)

# ---------- 3. 存储排查锁定规则 ----------
audit_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/StorageAuditViewModel.kt")
const = read("app/src/main/java/com/azu/bookkeeper/core/Constants.kt")
db_name = re.search(r'DATABASE_NAME = "([^"]+)"', const)
key_pref = re.search(r'DATABASE_KEY_PREF = "([^"]+)"', const)
check("T-AUDIT-01 当前数据库名取自 Constants", "Constants.DATABASE_NAME" in audit_vm)
check("T-AUDIT-02 数据库密钥取自 Constants", "Constants.DATABASE_KEY_PREF" in audit_vm)
check("T-AUDIT-03 当前库及旁路文件被锁定", "locked = isCurrent" in audit_vm)
check(
    "T-AUDIT-04 DataStore 文件被锁定",
    "locked = dir == dataStoreDir" in audit_vm,
)
rescan_body = audit_vm.split("fun rescan(")[1].split("fun deleteSelected(")[0] if "fun rescan(" in audit_vm else ""
check(
    "T-AUDIT-05 删除后提示不会被 rescan 清空",
    "clearMessage: Boolean = true" in rescan_body
    and "message = if (clearMessage) null else state.message" in rescan_body
    and "rescan(clearMessage = false)" in audit_vm,
    "删除后必须 rescan(clearMessage=false)，否则提示被 StateFlow 最后一次更新抹掉",
)
check("T-AUDIT-06 常量解析成功", bool(db_name and key_pref))

# ---------- 4. 转场时长上限 ----------
check("T-NAV-01 push 转场 <= 250ms", "PUSH_MS = 220" in navhost)
check("T-NAV-02 pop 转场 <= 250ms", "POP_MS = 180" in navhost)
check("T-NAV-03 tab 转场 <= 150ms", "TAB_MS = 120" in navhost)
check(
    "T-NAV-04 四个主 tab 均覆盖为淡入",
    navhost.count("enterTransition = tabEnter") == 4,
    f"实际 {navhost.count('enterTransition = tabEnter')} 处",
)
check(
    "T-NAV-05 底栏即时切换（避免布局二次跳变）",
    "AnimatedVisibility(" not in navhost and "if (showBottomBar)" in navhost,
)

# ---------- 5. 旧标记 / 编译期残留 ----------
all_kt = "\n".join(
    p.read_text(encoding="utf-8")
    for p in (ROOT / "app/src/main/java").rglob("*.kt")
)
for token in ("TextBackActionOnEmpty", "ColumnScopeLike", "ScrollableTabRow"):
    check(f"T-CLEAN-{token} 无残留", token not in all_kt)

# ---------- 6. 源文件结构 ----------
key_files = [
    "app/src/main/java/com/azu/bookkeeper/ui/profile/ProfileScreen.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/profile/ProfileViewModel.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/settings/StorageAuditScreen.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/settings/StorageAuditViewModel.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/settings/AboutScreen.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/stats/StatisticsScreen.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/stats/component/StatsSummaryCards.kt",
    "app/src/main/java/com/azu/bookkeeper/ui/navigation/AppNavHost.kt",
]
for rel in key_files:
    text = read(rel)
    check(f"T-FILE-{Path(rel).name} 存在且声明 package", bool(text) and "package com.azu.bookkeeper" in text)

# 括号配平（剔除注释与字符串）
def strip(src):
    out, i, n, state = [], 0, len(src), None
    while i < n:
        c = src[i]
        if state is None:
            if src.startswith("//", i):
                j = src.find("\n", i)
                i = n if j < 0 else j
                continue
            if src.startswith("/*", i):
                j = src.find("*/", i + 2)
                i = n if j < 0 else j + 2
                continue
            if src.startswith('"""', i):
                state, i = '"""', i + 3
                continue
            if c == '"':
                state, i = '"', i + 1
                continue
            if c == "'":
                state, i = "'", i + 1
                continue
            out.append(c)
            i += 1
        else:
            if state == '"""' and src.startswith('"""', i):
                state, i = None, i + 3
                continue
            if state in ('"', "'") and c == "\\":
                i += 2
                continue
            if state == '"' and c == '"':
                state, i = None, i + 1
                continue
            if state == "'" and c == "'":
                state, i = None, i + 1
                continue
            i += 1
    return "".join(out)


for rel in key_files:
    t = strip(read(rel))
    ok = all(t.count(a) == t.count(b) for a, b in (("{", "}"), ("(", ")"), ("[", "]")))
    check(f"T-SYNTAX-{Path(rel).name} 括号配平", ok)

# ---------- 7. 测试发现问题的回归断言 ----------
stats_vm = read("app/src/main/java/com/azu/bookkeeper/ui/stats/StatisticsViewModel.kt")
stats_screen = read("app/src/main/java/com/azu/bookkeeper/ui/stats/StatisticsScreen.kt")
use_case = read("app/src/main/java/com/azu/bookkeeper/domain/usecase/stats/GetStatisticsUseCase.kt")
settings_vm = read("app/src/main/java/com/azu/bookkeeper/ui/settings/SettingsViewModel.kt")
profile_vm = read("app/src/main/java/com/azu/bookkeeper/ui/profile/ProfileViewModel.kt")
summary_cards = read("app/src/main/java/com/azu/bookkeeper/ui/stats/component/StatsSummaryCards.kt")

check("T-FIX-01 我的页排除余额调整流水", "!it.isAdjustment" in profile_vm)
check("T-FIX-02 换账本清空账户数与预算", "budgetLimitFen = 0L" in profile_vm and "accountCount = 0" in profile_vm)
check("T-FIX-03 跨月范围不套单月预算", "val sameMonth" in use_case)
check("T-FIX-04 截图请求会被消费", "captureRequest = 0L" in stats_vm)
check("T-FIX-05 截图按钮只在图表页显示", "state.tab in 1..3" in stats_screen)
check("T-FIX-06 统计页进入时刷新", "fun refresh()" in stats_vm and "LaunchedEffect(Unit) { viewModel.refresh() }" in stats_screen)
check("T-FIX-07 统计加载串行化", "loadJob?.cancel()" in stats_vm)
check("T-FIX-08 账户下钻包含转账", "if (target.accountId != null)" in stats_vm)
check("T-FIX-09 统计提示自动消失", "viewModel.dismissMessage()" in stats_screen)
check("T-FIX-10 首页错误提示自动消失", "viewModel.consumeMessage()" in read("app/src/main/java/com/azu/bookkeeper/ui/home/HomeScreen.kt"))
check("T-FIX-11 底栏不再用 AnimatedVisibility", "AnimatedVisibility(" not in navhost)
check("T-FIX-12 预算百分比两页统一", "val percent =" in summary_cards)
check("T-FIX-13 PIN 校验纯数字", "pin.all { it.isDigit() }" in settings_vm)
analyzer = read("app/src/main/java/com/azu/bookkeeper/automation/ocr/PaymentTextAnalyzer.kt")
check(
    "T-FIX-14 纯商品标签行不再被当作商品名",
    "if (line in GOODS_BARE_LABELS) continue" in analyzer
    and "if (captured in GOODS_BARE_LABELS) continue" in analyzer,
)

# ---------- 输出 ----------
failed = [r for r in results if not r[1]]
for name, ok, detail in results:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not ok else ""))
print(f"\n总计 {len(results)} 项，通过 {len(results) - len(failed)} 项，失败 {len(failed)} 项")
sys.exit(1 if failed else 0)
