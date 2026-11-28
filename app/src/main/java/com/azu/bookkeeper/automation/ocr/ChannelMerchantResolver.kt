package com.azu.bookkeeper.automation.ocr

import com.azu.bookkeeper.core.SafeRegex

/**
 * 受 [规则一] 约束：财付通向上追溯真实商户。
 *
 * 只做一件事：当 OCR 文本里出现**独占一行**的「财付通」（通道名、本行没有具体商户），
 * 且前面的常规抽取都没拿到有效商户时，向上回溯最近 3 行非空白文本，
 * 按「小程序名 > 店铺/品牌名 > 含 SKU 的商品描述行」优先级取 1 个作为商户名。
 *
 * 为什么要回溯：微信账单详情把通道方「财付通支付科技有限公司」写在商户字段附近，
 * 旧逻辑把它当商户；真实商户（小程序 / 店铺）反而落在它上面的行里。
 *
 * 防错边界（受 [规则一] 约束）：
 * - 微信支付 / 财付通 / 支付服务 / 银行名 / 金额行 / 时间行 / 状态行一律不作为商户；
 * - 3 行内无有效命中返回 null，绝不用通道名回填；
 * - 出现「待支付 / 未支付」等未完成态时，不回填商户。
 *
 * 本对象是纯函数，不依赖 Android / Hilt，便于单测直接喂 [原文=] 样本行。
 */
object ChannelMerchantResolver {

    /**
     * 受 [规则一] 约束
     * @param lines OCR 文本行（可含空白，内部自行过滤）
     * @return 追溯到的商户名；无命中返回 null
     */
    fun backtrack(lines: List<String>): String? {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        // 受 [规则一] 约束：只在「一行内容为财付通」时触发
        val channelIndex = cleaned.indexOfFirst { it == CHANNEL_TOKEN }
        if (channelIndex < 0) return null
        // 受 [规则一] 约束：未完成态不回填商户
        if (cleaned.any { line -> PENDING_STATUS.any { it in line } }) return null

        val from = maxOf(0, channelIndex - LOOK_BACK_LINES)
        val candidates = cleaned.subList(from, channelIndex)
            .filter { isValidCandidate(it) }

        // 受 [规则一] 约束：优先级 1 —— 小程序名称
        candidates.firstOrNull { it.contains(MINI_PROGRAM_TOKEN) }?.let { return clean(it) }
        // 受 [规则一] 约束：优先级 2 —— 店铺名 / 品牌名（非银行、非通道）
        candidates.firstOrNull { looksLikeShopOrBrand(it) }?.let { return clean(it) }
        // 受 [规则一] 约束：优先级 3 —— 商品描述行（含具体 SKU）
        candidates.firstOrNull { hasSku(it) }?.let { return clean(it) }
        return null
    }

    /** 受 [规则一] 约束：排除通道 / 银行 / 金额 / 时间 / 状态 / UI 按钮行。 */
    private fun isValidCandidate(line: String): Boolean {
        if (line.length !in 2..MAX_NAME_LENGTH) return false
        if (GENERIC_CHANNEL.any { it in line }) return false
        if (BANK_TOKEN in line) return false
        if (STATUS_TOKENS.any { it in line }) return false
        if (UI_WORDS.any { it == line }) return false
        if (AMOUNT_LINE.matches(line)) return false
        if (DATE_LINE.containsMatchIn(line)) return false
        return true
    }

    /** 受 [规则一] 约束：店铺 / 品牌名需要有可识别的业态后缀，避免抓到任意文本行。 */
    private fun looksLikeShopOrBrand(line: String): Boolean {
        if (SHOP_SUFFIXES.any { it in line }) return true
        // 中英混排的公司名（如 「喜茶 HEYTEA」）也算品牌行
        val hasCjk = line.any { it.code in 0x4E00..0x9FFF }
        val hasLatin = line.any { it in 'A'..'z' }
        return hasCjk && hasLatin && line.length in 2..MAX_NAME_LENGTH
    }

    /** 受 [规则一] 约束：商品描述行需含字母+数字混合的 SKU，或显式 SKU 标签。 */
    private fun hasSku(line: String): Boolean =
        line.contains(SKU_LABEL, ignoreCase = true) || SKU_TOKEN.containsMatchIn(line)

    private fun clean(value: String): String =
        value.trim().trim('：', ':', '·', '-', '—', '|', '，', ',').trim()

    private const val CHANNEL_TOKEN = "财付通"
    private const val MINI_PROGRAM_TOKEN = "小程序"
    private const val BANK_TOKEN = "银行"
    private const val SKU_LABEL = "SKU"
    private const val MAX_NAME_LENGTH = 40
    private const val LOOK_BACK_LINES = 3

    /** 受 [规则一] 约束：禁止作为商户的通用通道名。 */
    private val GENERIC_CHANNEL = listOf(
        "微信支付", "财付通", "支付服务", "支付机构", "收单机构", "清算机构", "银联"
    )
    /** 受 [规则一] 约束：状态行不作为商户。 */
    private val STATUS_TOKENS = listOf(
        "支付成功", "付款成功", "扣款成功", "交易成功", "已支付", "支付完成", "交易完成",
        "支付失败", "已退款", "已取消", "待收货", "已完成"
    )
    /** 受 [规则一] 约束：未完成态出现时不回填商户。 */
    private val PENDING_STATUS = listOf("待支付", "未支付", "待付款", "支付中", "处理中", "待确认")
    private val UI_WORDS = setOf(
        "返回", "完成", "取消", "确认", "更多", "详情", "查看", "支付", "付款", "收款",
        "交易", "账单", "订单", "明细", "记录", "成功", "失败", "全屏", "客服"
    )
    private val SHOP_SUFFIXES = listOf(
        "店", "铺", "馆", "坊", "超市", "便利", "咖啡", "茶", "餐饮", "旗舰店", "专营店",
        "商行", "中心", "药房", "医院", "酒店", "食堂", "公司", "Ltd", "Inc"
    )
    private val AMOUNT_LINE = SafeRegex.of("^[-+−]?(?:¥|￥)?[0-9][0-9,]*(?:\\.[0-9]{1,2})?元?$")
    private val DATE_LINE = SafeRegex.of("20\\d{2}[-/年]\\d{1,2}[-/月]\\d{1,2}")
    private val SKU_TOKEN = SafeRegex.of("[A-Za-z]{1,8}[-_]?[0-9]{2,}")
}
