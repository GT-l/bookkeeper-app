package com.azu.bookkeeper.core

/** 全局常量：数据库、DataStore、导入批处理、自动化默认值。 */
object Constants {

    // ---------- 数据库 ----------
    const val DATABASE_NAME = "bookkeeper.db"
    const val DATABASE_VERSION = 2
    const val DATABASE_KEY_PREF = "bk_db_key_prefs"
    const val DATABASE_KEY_ENTRY = "passphrase"

    // ---------- DataStore ----------
    const val SETTINGS_DATASTORE = "bookkeeper_settings"
    const val AUTOMATION_DATASTORE = "automation_settings"

    // ---------- 文件目录 ----------
    const val DIR_CAPTURES = "captures"
    const val DIR_BACKUPS = "backups"
    const val DIR_IMPORTS = "imports"
    const val MAX_IMPORT_FILE_BYTES = 10L * 1024L * 1024L

    // ---------- 导入 / 批量 ----------
    const val IMPORT_BATCH_SIZE = 500
    const val MAX_TAGS_PER_BILL = 10
    const val MAX_IMAGES_PER_BILL = 9

    // ---------- 自动化默认值 ----------
    const val DEFAULT_OCR_THRESHOLD = 0.75f
    const val MIN_OCR_THRESHOLD = 0.5f
    const val MAX_OCR_THRESHOLD = 0.95f
    const val CAPTURE_DEBOUNCE_MS = 1500L
    const val SERVICE_RESTART_INTERVAL_MIN = 15L

    /** 短信源容差去重窗口（秒），DataStore 可配 0~600；仅短信源启用。 */
    const val SMS_DEDUP_TOLERANCE_SEC = 180L

    /** 支付类应用包名白名单，T5 的读屏 / 通知捕获共用。 */
    val PAYMENT_PACKAGES = listOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.chinapay.pay",
        "com.icbc",
        "com.ccb",
        "com.android.bankccc",
        // 抖音免密支付（T6：packageName + 种子规则，不新增 captureType 枚举）
        "com.ss.android.ugc.aweme"
    )

    /** 支付完成态关键词：命中才触发自动入账，中间态不触发。 */
    val PAYMENT_SUCCESS_KEYWORDS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "已支付", "支付完成"
    )

    /**
     * 捕获 / 记账来源字符串协议（T5/T6/T7 消费）。
     * 与 BillSource 的 code 并列：sms / douyin / quick_manual 为本次新增预留，
     * 其中 douyin 是捕获渠道标识（走 packageName + 种子规则），不落为 BillSource 枚举值。
     */
    object CaptureSources {
        const val MANUAL = "manual"
        const val IMPORT = "import"
        const val AUTO_CAPTURE = "auto_capture"
        const val SMS = "sms"
        const val DOUYIN = "douyin"
        const val QUICK_MANUAL = "quick_manual"

        val ALL: List<String> = listOf(MANUAL, IMPORT, AUTO_CAPTURE, SMS, DOUYIN, QUICK_MANUAL)
    }

    // ---------- 通知渠道 ----------
    const val CHANNEL_AUTO_CAPTURE = "channel_auto_capture"
    const val CHANNEL_VERIFY = "channel_verify"
    const val CHANNEL_REMINDER = "channel_reminder"

    /** 静态快捷方式 / 小组件跳转时携带的 intent extra，用于打开表单预填。 */
    const val EXTRA_QUICK_ADD = "com.azu.bookkeeper.extra.QUICK_ADD"

    /** 低置信度校对通知 → MainActivity → BillEdit 的预填 JSON extra。 */
    const val EXTRA_VERIFY_PREFILL = "com.azu.bookkeeper.extra.VERIFY_PREFILL"

    /** 自动化通知 ID 与前台服务 ID。 */
    const val FOREGROUND_SERVICE_ID = 1001
    const val VERIFY_NOTIFICATION_ID = 1002
    const val AUTO_BILL_NOTIFICATION_ID = 1003
}
