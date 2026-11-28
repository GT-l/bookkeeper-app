package com.azu.bookkeeper.automation.ocr

/**
 * 受 [规则二] 约束：商品名 OCR 英文误识别提醒（音译乱码 / 英文被拆碎混入汉字）。
 *
 * 只分析「商品名 / 商品描述」这一个字段，商户名与通道名不进这里（受 [规则二] 防错边界约束）。
 * 命中后只输出固定提醒 + 写日志，**不阻断记账流程**。
 *
 * 实现范围（受工单红线「无 原文= 日志支撑的规则一律不写、不猜测」约束）：
 * - 条件 A（音译乱码）：有 4 条验证样本中的「艾尔普兹无线 / 苹果无线耳机」支撑 → 实现；
 * - 条件 C（英文拆碎混入汉字）：有「A艾尔P兹 / 苹果AirPods Pro」支撑 → 实现；
 * - 条件 B（纯拼音伪装）：工单**未给任何 原文= 样本**，且缺少可靠的拼音词典，
 *   强行实现会误伤「纯英文」白名单 → 本轮**跳过**，原因见 docs/testing/v1.3.21/TEST_CASES.md。
 *
 * 白名单（受 [规则二] 约束）：纯英文、有中文主体的中英混合、有语义的纯中文。
 */
object GoodsOcrAnomaly {

    /** 受 [规则二] 约束：固定提醒文案，不得随意改写。 */
    const val WARNING = "⚠️ 识别失败：商品名可能被误识别，建议人工核对"

    /**
     * 受 [规则二] 约束
     * @param goods 已完成 mergeLatinContinuation 拼接后的商品名，非空
     * @return true = 命中条件 A 或 C，需要输出提醒
     */
    fun detect(goods: String): Boolean {
        val value = goods.trim()
        if (value.isEmpty()) return false
        // 白名单：纯英文（AirPods Pro）不触发
        if (value.none { it.code in CJK_START..CJK_END }) return false
        return conditionA(value) || conditionC(value)
    }

    /**
     * 条件 A：全汉字且音译高频字占显著比例。
     * 受 [规则二] 约束——样本「艾尔普兹无线」命中（4/6），「苹果无线耳机」放行（0/6）。
     */
    private fun conditionA(value: String): Boolean {
        if (value.any { it.code !in CJK_START..CJK_END }) return false
        val translitCount = value.count { it in TRANSLIT_CHARS }
        return translitCount >= 2 && translitCount * 2 >= value.length
    }

    /**
     * 条件 C：孤立的单字母英文片段（≥2 个）+ 含音译高频字的汉字串。
     * 受 [规则二] 约束——样本「A艾尔P兹」命中；「苹果AirPods Pro」拉丁片段长度 ≥2，放行。
     */
    private fun conditionC(value: String): Boolean {
        val chars = value.toList()
        // 逐字符判断「孤立」：左右都不是拉丁字母
        val isolated = chars.indices.count { i ->
            val c = chars[i]
            val isLatin = c in 'A'..'Z' || c in 'a'..'z'
            isLatin && (i == 0 || !chars[i - 1].isLatinLetter()) &&
                (i == chars.lastIndex || !chars[i + 1].isLatinLetter())
        }
        if (isolated < 2) return false
        val cjkCount = chars.count { it.code in CJK_START..CJK_END }
        val translitCount = chars.count { it in TRANSLIT_CHARS }
        return cjkCount >= 2 && translitCount >= 2
    }

    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private const val CJK_START = 0x4E00
    private const val CJK_END = 0x9FFF

    /** 受 [规则二] 约束：音译高频字，逐字取自工单条件 A 给出的集合。 */
    private val TRANSLIT_CHARS = setOf('艾', '尔', '普', '兹', '克', '斯', '特', '德', '阿', '纳', '菲')
}
