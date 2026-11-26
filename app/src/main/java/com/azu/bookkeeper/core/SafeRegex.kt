package com.azu.bookkeeper.core

/**
 * 正则安全编译。
 *
 * 为什么必须有这个：Android 的正则底层是 ICU，不同 ROM / 系统版本对同一个 pattern
 * 的解析结果并不一致。一旦某条正则在 `companion object` 或 `object` 的**初始化阶段**
 * 抛 `PatternSyntaxException`，Kotlin 会把它包装成 `ExceptionInInitializerError`——
 * 之后**任何引用该类的地方都会立刻崩溃**，而且栈里只显示类的 `<clinit>`，
 * 完全看不出是哪条正则。Hilt 创建注入了它的 ViewModel 时就会表现为「点开页面直接闪退」。
 *
 * 真实事故（Android 17 / realme RMX3888）：
 * ```
 * Caused by: java.util.regex.PatternSyntaxException: Incorrect Unicode property near index 70
 *   (?:收款方|…)\s*[:：]?\s*([^\s，。；;：:]{2,30})
 *                                         ^
 *   at com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer.<clinit>
 * ```
 * → 打开「识屏自检」页时 ViewModel 创建失败 → App 闪退。
 *
 * 规则：**所有可能在类初始化期编译的正则，一律走 [of]**。
 * 编译失败时降级为「永不匹配」，相关功能失效，但 App 绝不崩。
 */
object SafeRegex {

    /** 永不匹配的哨兵：字面量是正常文本不可能出现的串，避免任何特殊语法。 */
    private val NEVER: Regex by lazy {
        runCatching { Regex(Regex.escape("\u0000__safe_regex_never__\u0000")) }
            .getOrElse { Regex("(?!)") }
    }

    /** 安全编译；失败返回永不匹配的正则（并留痕）。 */
    fun of(pattern: String): Regex = runCatching { Regex(pattern) }.getOrElse { t ->
        runCatching { timber.log.Timber.w(t, "正则编译失败，已降级为永不匹配: %s", pattern) }
        NEVER
    }

    /**
     * 字符类里禁用 `\s`。
     *
     * 原因见类注释：`[^\s，。；;：:]` 这种「\s + 全角标点」混排，在部分 ICU 版本上
     * 会被误判为 Unicode property 而直接抛异常。统一用显式空白集合代替，
     * 语义等价（空格 / 制表 / 全角空格），且在所有版本上都能编译。
     */
    const val BLANK_CLASS = " \\t\u00a0\u3000"
}
