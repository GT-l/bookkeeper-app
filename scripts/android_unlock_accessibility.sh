#!/usr/bin/env bash
# 少花点儿 · 一次性解除 Android 13+ 侧载「受限设置」，并登记无障碍服务
#
# 适用现场：Android 13+（含 Android 17 / API 37）从 APK / GitHub 侧载安装后，
# 无障碍开关能打开、几秒后又被系统自动关闭。进程 pid 不变说明不是后台被杀，
# 而是系统撤销授权；后台保活类设置对这种现场无效。
#
# 用法：
#   ./scripts/android_unlock_accessibility.sh status    # 只读，看当前状态
#   ./scripts/android_unlock_accessibility.sh unlock    # 解除受限设置 + 追加无障碍服务
#   ./scripts/android_unlock_accessibility.sh check     # 验证是否生效
#
# 前提：手机开启「开发者选项 → USB 调试」（或用无线调试 adb connect），电脑装好 adb。
# 不需要 root，不需要解锁 bootloader；这是一次性授权，之后正常使用不再依赖 adb。
set -uo pipefail

PKG="com.azu.bookkeeper"
SERVICE="$PKG/com.azu.bookkeeper.automation.service.PaymentAccessibilityService"
APPOP="ACCESS_RESTRICTED_SETTINGS"
ACTION="${1:-status}"

if ! command -v adb >/dev/null 2>&1; then
  echo "FAIL: 找不到 adb。请安装 Android SDK platform-tools，或把 adb 加入 PATH。"
  exit 1
fi

if [ -z "$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')" ]; then
  echo "FAIL: 没有已授权的 adb 设备。请确认 USB 调试已开启并在手机上点了「允许调试」。"
  exit 1
fi

if ! adb shell pm path "$PKG" >/dev/null 2>&1; then
  echo "FAIL: 设备上没有安装 $PKG，请先安装 APK。"
  exit 1
fi

SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "设备 Android API: ${SDK:-未知}  包名: $PKG"

show_status() {
  echo "--- 受限设置 appop ---"
  adb shell appops get "$PKG" "$APPOP" 2>&1 | tr -d '\r'
  echo "--- 已启用的无障碍服务 ---"
  adb shell settings get secure enabled_accessibility_services 2>&1 | tr -d '\r'
  echo "--- 无障碍总开关 ---"
  adb shell settings get secure accessibility_enabled 2>&1 | tr -d '\r'
}

set_appop() {
  # 不同 ROM 对 --user 参数支持不一致：先带 --user 0，失败再退回不带
  if adb shell appops set --user 0 "$PKG" "$APPOP" allow >/dev/null 2>&1; then
    return 0
  fi
  adb shell appops set "$PKG" "$APPOP" allow >/dev/null 2>&1
}

case "$ACTION" in
  status)
    show_status
    echo
    echo "若 appop 不是 allow，执行: $0 unlock"
    ;;

  unlock)
    echo "[1/3] 解除「允许受限设置」..."
    if ! set_appop; then
      echo "WARN: appops set $APPOP 失败；部分 ROM 改用完整名，重试 android:access_restricted_settings"
      adb shell appops set "$PKG" android:access_restricted_settings allow 2>&1 | tr -d '\r'
    fi

    echo "[2/3] 追加无障碍服务（保留已有的其它服务，不覆盖）..."
    CURRENT="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
    [ "$CURRENT" = "null" ] && CURRENT=""
    case ":$CURRENT:" in
      *":$SERVICE:"*)
        echo "     已在列表中，跳过"
        ;;
      *)
        if [ -z "$CURRENT" ]; then
          NEW="$SERVICE"
        else
          NEW="$CURRENT:$SERVICE"
        fi
        adb shell settings put secure enabled_accessibility_services "$NEW"
        echo "     已写入: $NEW"
        ;;
    esac
    # accessibility_enabled 由系统依据上面的列表维护，这里只确保它是 1；
    # 不写也不会影响系统重新绑定服务，写失败不视为致命错误。
    adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true

    echo "[3/3] 复核..."
    show_status
    echo
    echo "完成。请回到「设置 → 快捷与辅助 → 无障碍 → 已下载的服务」确认少花点儿开关，"
    echo "然后打开一次 App，静置 5 分钟看是否还会被自动关闭。"
    echo
    echo "若仍会被关：检查「设置 → 安全 → 高级保护模式」是否开启（Android 17 会强制撤销"
    echo "非辅助类应用的无障碍权限），或改用 Shizuku / Shevery 的无障碍服务锁定。"
    ;;

  check)
    OPS="$(adb shell appops get "$PKG" "$APPOP" 2>&1 | tr -d '\r')"
    LIST="$(adb shell settings get secure enabled_accessibility_services 2>&1 | tr -d '\r')"
    ok=1
    case "$OPS" in
      *allow*) echo "PASS 受限设置已解除: $OPS" ;;
      *)       echo "FAIL 受限设置未解除: $OPS"; ok=0 ;;
    esac
    case "$LIST" in
      *PaymentAccessibilityService*) echo "PASS 无障碍服务已在列表: $LIST" ;;
      *)                            echo "FAIL 无障碍服务不在列表: $LIST"; ok=0 ;;
    esac
    [ "$ok" -eq 1 ] || exit 1
    ;;

  *)
    echo "用法: $0 {status|unlock|check}"
    exit 2
    ;;
esac
