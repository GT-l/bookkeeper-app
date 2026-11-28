package com.azu.bookkeeper.automation.capture

import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.SafeRegex

/** 支付相关页面类型。ENTRY = 付款界面（收银台/付款码/输密码），SUCCESS = 支付结果页。 */
enum class PaymentScreen {
    ENTRY, SUCCESS, DETAIL, UNKNOWN
}

/** 记账方向：转账/红包有来有回，收款必须记收入，不能一律当支出。 */
enum class BillDirection { EXPENSE, INCOME, NEUTRAL }

/**
 * 支付场景（v1.3.6）。市面主链路最终都收敛到微信 / 支付宝 / 抖音 / 银行 App，
 * 这里按「谁 + 什么动作 + 钱进还是出」分类，供日志、方向判定与后续规则细化使用。
 */
enum class PaymentScene {
    WECHAT_TRANSFER_OUT, WECHAT_TRANSFER_IN,
    WECHAT_RED_PACKET_OUT, WECHAT_RED_PACKET_IN,
    WECHAT_MERCHANT_PAY, WECHAT_BILL_DETAIL, WECHAT_WITHDRAW,
    ALIPAY_TRANSFER_OUT, ALIPAY_TRANSFER_IN,
    ALIPAY_RED_PACKET_OUT, ALIPAY_RED_PACKET_IN,
    ALIPAY_MERCHANT_PAY, ALIPAY_BILL_DETAIL, ALIPAY_WITHDRAW,
    UNIONPAY_PAY, DOUYIN_ORDER, BANK_TRANSFER,
    /** v1.3.8：提现 / 充值 / 零钱通·余额宝划转——钱只在自有账户之间搬家，不计收支。 */
    INTERNAL_TRANSFER,
    UNKNOWN
}

/**
 * 支付页面识别器（纯 Kotlin，不依赖 Android API，可直接跑 JVM 单测）。
 *
 * 为什么要单独做这一层：
 * 1. 旧实现只会问「整页文本里有没有 6 个完成态关键词」，既认不出「用户刚打开付款界面」，
 *    也认不出微信的「已完成 / 微信支付 / 已存入对方零钱」等文案；
 * 2. 市面同类实现（花有谱、BillRecord）都把 **Activity 类名** 当作强信号：
 *    部分微信支付成功页在普通无障碍服务下只返回空根节点，此时类名是唯一线索；
 * 3. 微信支付/小程序常跑在 `com.tencent.mm:tools` 之类子进程，需要先把包名归一化。
 *
 * 识别顺序：结果页类名 → 付款界面类名 → 完成态文案 → **账单详情** → 付款界面文案。
 * v1.3.1：账单详情必须排在付款界面之前（详情页普遍带「付款方式」字样，旧顺序会误判 ENTRY
 * 并被直接丢弃，导致「打开同一笔付款记录」永远触发不了重复检测）。
 * 强否定词（支付失败 / 已取消 / 已退款）不会单独把页面判成成功，只降低置信度。
 */
object PaymentScreenRecognizer {

    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val DOUYIN = "com.ss.android.ugc.aweme"

    /** v1.3.8：云闪付（一木/钱迹都把它当主要场景，白名单里一直没有）。 */
    const val UNIONPAY = "com.unionpay"

    data class Recognition(
        val screen: PaymentScreen,
        val reason: String,
        val confidence: Float,
        val scene: PaymentScene = PaymentScene.UNKNOWN,
        val direction: BillDirection = BillDirection.EXPENSE
    )

    /**
     * 结果页 Activity 特征。微信的 WalletPayResultUI、支付宝的 PayResultActivity 都命中这一组。
     *
     * v1.3.8：只收「结果 / 详情」这类明确的类名。
     * 裸的 `Remittance` / `LuckyMoney` 会连「转账输入页」「红包编辑页」一起命中，
     * 等于用户刚点开转账就记了一笔，比漏识别更糟。
     */
    private val SUCCESS_CLASS_TOKENS = listOf(
        "WalletPayResult", "PayResult", "PaySuccess", "PayComplete", "PayDone", "PayFinish",
        // v1.3.6：微信/支付宝的转账、收款结果页（用户反馈「转账成功界面识别不了」）
        "TransferResult", "TransferMoneyResult", "RemitResult", "ReceiptResult",
        "TransferDetail", "WalletTransfer", "Receipt",
        // v1.3.8：微信转账走 remittance 模块、红包走 luckymoney 模块，只认结果/详情页
        "RemittanceResult", "RemittanceDetail", "LuckyMoneyDetail",
        "RedPacketDetail", "RedPacketResult"
    )

    /** 付款界面 Activity 特征：收银台、付款码、输密码、选支付方式。 */
    private val ENTRY_CLASS_TOKENS = listOf(
        "WalletPayUI", "Cashier", "PayPassword", "PayInput", "SelectPay",
        "PayActivity", "PaymentActivity", "QRPay", "FacePay",
        // v1.3.8：转账输入页 / 红包编辑页是中间态，绝不能因为类名相似被当成结果页
        "RemittanceUI", "TransferInput", "TransferEdit",
        "LuckyMoneyPrepare", "LuckyMoneyEdit", "RedPacketPrepare"
    )

    /**
     * v1.3.8：聊天页 / 微信主界面。
     *
     * 这两个页面里必然出现「微信支付」（服务号名称）和转账消息气泡
     * （「转账」「已收款」「对方已收款」）。旧词表会把它判成「支付成功」直接记账，
     * 是最隐蔽的脏账来源。整页跳过。
     */
    private val CHAT_CLASS_TOKENS = listOf(
        "ChattingUI", "Chatting", "LauncherUI", "ConversationUI", "ContactInfoUI"
    )

    /** 红包类名（只用于标注场景，不单独作为「成功」依据）。 */
    private val RED_PACKET_CLASS_TOKENS = listOf(
        "LuckyMoney", "RedPacket", "RedEnvelope", "Hongbao"
    )

    /** 通用完成态：保留原有 6 个，补齐常见变体。 */
    private val COMMON_SUCCESS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "已支付", "支付完成",
        "扣款成功", "支付已完成", "付款完成", "已付款", "支付完毕", "成功支付"
    )

    /**
     * 微信专属完成态。
     *
     * v1.3.8：去掉裸的「已领取」——微信卡包 / 优惠券 / 小程序也写「已领取」，
     * 脱离上下文命中会把领券记成支付成功。红包改为「红包上下文 + 领取信号」双条件。
     */
    private val WECHAT_SUCCESS = listOf(
        "微信支付", "已完成", "已存入对方零钱", "转账已发出", "红包已发送",
        "支付成功通知", "已收款", "付款成功", "支付成功",
        // v1.3.6：微信收款成功实际写的是「已收钱」（不是「已收款」），
        // 转账页顶部还会写「转账成功 / 已到账」，旧词表全部漏掉。
        "已收钱", "收款成功", "已到账", "转账成功", "对方已收款",
        // v1.3.8：红包收 / 发与零钱到账
        "红包已领取", "红包已发出", "已存入零钱", "已存入你的零钱", "已发送红包"
    )

    /** 付款界面文案。命中即认为「用户打开了付款界面」，不触发记账。 */
    private val ENTRY_TEXT = listOf(
        "付款码", "向商家付款", "收付款", "确认支付", "请输入支付密码", "立即支付",
        "待支付", "收银台", "选择付款方式", "支付金额", "付款方式", "请付款", "确认付款",
        "使用密码", "指纹支付",
        // v1.3.6：转账中间态——收款方还没点确认，钱没真正到账，不能记账
        "待确认收款", "确认收款", "等待对方确认收款", "立即退还", "待对方确认"
    )

    /** 红包上下文：红包页即使不写「红包」二字，也会出现这些（拆红包页的祝福语等）。 */
    private val RED_PACKET_CONTEXT = listOf(
        "红包", "压岁钱", "拼手气", "手气最佳", "恭喜发财", "大吉大利", "红包封面"
    )

    /** 发红包（支出）：微信「红包已发送」，支付宝「红包已发出」。 */
    private val RED_PACKET_OUT_HINTS = listOf(
        "红包已发送", "红包已发出", "已发送红包", "发出的红包", "发了一个红包", "红包已存入对方"
    )

    /** 收红包（收入）：拆开后写「已存入零钱」，红包详情写「已领取」。 */
    private val RED_PACKET_IN_HINTS = listOf(
        "红包已领取", "已领取红包", "领取了", "已拆开", "红包已收", "红包已收下",
        "已存入零钱", "已存入你的零钱", "已存入余额"
    )

    /**
     * 转账转出（支出）。
     * 「对方已收款」是**转出方**看到的提示（我把钱转给他、他收了），属于支出；
     * v1.3.6 把它错放进收入表，导致转出去的账被记成收入，这里纠正。
     */
    private val TRANSFER_OUT_HINTS = listOf(
        "已存入对方零钱", "转账已发出", "转账成功", "已转账", "转账给", "已发出转账",
        "对方已收款", "对方已领取", "已转入对方"
    )

    /** 转账收款（收入）：微信写「已收钱」，支付宝写「收款成功」。 */
    private val TRANSFER_IN_HINTS = listOf(
        "已收钱", "已收款", "收款成功", "收到转账", "转账已接收", "已接收转账", "转账已收",
        "已存入零钱", "收款到账", "已存入余额"
    )

    /**
     * 提现 / 充值 / 零钱通、余额宝划转（v1.3.8，对齐一木记账的「提现」场景）。
     * 钱只是在自有账户之间搬家，记成支出会把总支出撑大一倍 → 判 NEUTRAL 不入账。
     */
    private val WITHDRAW_HINTS = listOf(
        "提现成功", "零钱提现", "提现到账", "提现已到账", "已到账银行卡", "提现受理",
        "充值成功", "零钱充值", "转入零钱通", "转出零钱通",
        "余额宝转入", "余额宝转出", "转出到余额", "转入余额宝"
    )

    /** 转账中间态：收款方还没点「确认收款」，钱没真正到账。 */
    private val PENDING_TRANSFER = listOf(
        "待确认收款", "确认收款", "等待对方确认收款", "立即退还", "待对方确认"
    )

    private val NEGATIVE = listOf(
        "支付失败", "付款失败", "已取消", "已退款", "支付超时", "订单关闭", "交易关闭"
    )

    /**
     * v1.3.5：用户从「消费记录 / 账单」点进某笔交易时，页面往往不写「账单详情」，
     * 只留一组字段（交易单号、商户单号、支付时间、商品说明）。
     * 旧词表认不出 → 判 UNKNOWN → 直接丢弃，表现为「打开消费记录自动识别失败，必须用手势」。
     */
    private val DETAIL_STRONG = listOf(
        "账单详情", "交易详情", "订单详情", "转账详情",
        "交易单号", "商户单号", "支付时间", "商品说明", "转账单号", "订单编号"
    )

    /** 较弱的记录类词：列表页也会出现，必须配合「金额行数为个位数」才当单笔详情。 */
    private val DETAIL_WEAK = listOf(
        "消费记录", "交易记录", "收支明细", "账单记录", "转账记录", "扣款记录", "支付记录"
    )

    private val RECEIPT_CONTEXT = listOf(
        "交易单号", "商户单号", "账单详情", "交易详情", "订单详情", "收款方",
        "支付时间", "商品说明", "订单编号", "转账单号"
    )

    /**
     * 抖音订单页专属（v1.3.1）：抖音电商完成页常只写「实付 ¥xx」+「订单详情」，
     * 没有「支付成功 / 交易成功」这类通用完成词，旧词表直接判 UNKNOWN → 不提交。
     * 类名 token 只取订单/交易结果页，不取收银台，避免把付款中间态误判成功。
     */
    private val DOUYIN_RESULT_CLASS_TOKENS = listOf(
        "OrderDetail", "TradeDetail", "OrderResult", "TradeResult", "PaymentResult",
        "OrderPayResult", "DouyinPayResult"
    )
    private val DOUYIN_SUCCESS_HINTS = listOf(
        "实付", "订单支付成功", "交易完成", "下单成功", "订单完成", "支付完成"
    )
    /** 「实付」单独出现不够（收银台也有），必须带订单上下文才算完成态。 */
    private val DOUYIN_ORDER_CONTEXT = listOf(
        "订单", "交易", "已支付", "完成", "账单", "收货", "待评价"
    )

    /** 结果页里常见的无关按钮/文案，不能当商户名。 */
    private val NOISE_LINES = setOf(
        "完成", "返回", "查看订单", "查看账单", "知道了", "确定", "支付成功", "已完成",
        "微信支付", "付款成功", "查看明细", "再买一笔", "联系商家", "返回商家",
        // v1.3.1：这些短词常是页面标题/分区名，抽成商户会产生「只记到『支付』」的脏账单
        "支付", "付款", "账单", "详情", "零钱", "微信", "支付宝", "余额", "收款", "转账",
        "交易", "订单", "金额", "支付方式", "付款方式", "服务", "商品", "说明", "时间",
        // v1.3.10：「商户全称」被拆成两个节点时的残片，兜底猜测也不能取
        "全称", "简称", "名称"
    )

    /**
     * v1.3.9：商户值标签。长标签在前，避免「商户」先匹配掉「商户全称」。
     * 微信账单详情是「标签一行、值下一行」，两种版式都要能取到值。
     */
    private val MERCHANT_VALUE_LABELS = listOf(
        "商户全称", "商户简称", "商户名称", "商户", "商家", "收款方", "收款人",
        "交易对方", "付款给", "付款至"
    )

    /**
     * v1.3.10：标签残片，永远不能当商户值。
     *
     * 微信无障碍会把「商户全称」拆成「商户」+「全称」两个节点：
     * `removePrefix("商户")` 后 inline = 「全称」，长度恰好 2，旧实现直接当成商户名。
     * 用户反馈「识别到个全称而不是商户名」就是这个。
     */
    private val MERCHANT_LABEL_FRAGMENTS = setOf(
        "全称", "简称", "名称", "商户全称", "商户简称", "商户名称"
    )

    /** v1.3.11：交易时间标签。按记录时间判重的前提是能稳定读到它。 */
    private val TIME_LABEL_TOKENS = listOf(
        "支付时间", "付款时间", "转账时间", "收款时间", "交易时间",
        "到账时间", "创建时间", "下单时间", "发生时间"
    )

    /** 日期时间子串（含可选秒），与 FieldExtractor 保持同一形态。 */
    private val TIME_TOKEN = SafeRegex.of(
        """20\d{2}[-/年]\d{1,2}[-/月]\d{1,2}日?(?:[ \t\u00a0\u3000]+\d{1,2}:\d{2}(?::\d{2})?)?"""
    )

    /**
     * v1.3.9：收单机构类字段。它是通道方（财付通 / 银联商务），不是商户。
     * 页面上出现它时不再「猜第一行文字」，否则商户名会变成「财付通支付科技有限公司」。
     */
    private val ACQUIRER_LABELS = listOf("收单机构", "收单方", "支付机构", "清算机构", "特约商户")

    /** 余额 / 优惠 / 积分 / 退款不是本次实付，不能当金额或商户。 */
    private val NON_PAYMENT_LINE_HINTS = listOf("余额", "优惠", "积分", "退款", "返现", "可用", "剩余")

    /** 去掉微信子进程后缀：`com.tencent.mm:tools` → `com.tencent.mm`。 */
    fun normalizePackage(raw: String): String = raw.substringBefore(':').trim()

    /** 是否支付类应用（归一化后匹配 Constants.PAYMENT_PACKAGES）。 */
    fun isPaymentPackage(raw: String): Boolean =
        normalizePackage(raw) in Constants.PAYMENT_PACKAGES

    /**
     * 供通知 / 短信等非读屏链路复用的纯文本完成态判定。
     * v1.3.8：中间态（待确认收款）和否定态（支付失败）一律不算完成。
     */
    fun isSuccessText(text: String): Boolean {
        if (text.isBlank()) return false
        val compact = text.trim().replace("\n", "").replace(" ", "")
        if (PENDING_TRANSFER.any { compact.contains(it) }) return false
        if (NEGATIVE.any { compact.contains(it) }) return false
        return (COMMON_SUCCESS + WECHAT_SUCCESS).any { compact.contains(it) }
    }

    /** 是否账单详情页文案：详情页也要提交捕获，才能触发「同一笔已记过」的轻提醒。 */
    fun isDetailText(text: String): Boolean =
        DETAIL_STRONG.any { text.contains(it) } || DETAIL_WEAK.any { text.contains(it) }

    /**
     * 是否像一张「单笔消费凭据」：有金额 + 有交易单号/商户/订单等强上下文。
     * 用于成功词、详情词都没命中的兜底——打开消费记录时至少还能进低置信度确认，
     * 而不是静默丢掉。微信聊天页里出现「¥100」不会同时带「交易单号」，不会误触发。
     */
    fun looksLikeReceipt(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hasMoney = lines.any { looksLikeAmount(it) } ||
            text.contains('¥') || text.contains('￥')
        if (!hasMoney) return false
        return RECEIPT_CONTEXT.any { text.contains(it) }
    }

    /**
     * v1.3.18：这段文本里有没有「交易证据」——金额，或交易单号/商户/订单等凭据上下文。
     *
     * 用途：决定「节点采集够不够」「要不要退截图 OCR」。
     * 以前这两个判断只看 `ScreenTextCollector.isSparse`（行数 < 3 或长度 < 8）：
     * 微信这类自绘页面常返回「返回 / 支付 / 完成」几句无关文本——既不稀疏、也没有金额，
     * 于是既不会继续读其它窗口，也不会触发截图 OCR，正好卡在中间什么都不做。
     */
    fun hasTransactionSignal(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains('¥') || text.contains('￥')) return true
        if (text.lines().any { looksLikeAmount(it.trim()) }) return true
        return RECEIPT_CONTEXT.any { text.contains(it) }
    }

    /** 单笔详情页里的金额行数量：列表页会有很多行，借此避免把账单列表当成一笔。 */
    private fun amountLineCount(text: String): Int =
        text.lines().count { looksLikeAmount(it.trim()) }

    /** 类名是否像支付结果页：文本为空时用它触发有界重读。 */
    fun looksLikeResultClass(className: String): Boolean =
        SUCCESS_CLASS_TOKENS.any { className.contains(it, ignoreCase = true) }

    fun classify(packageName: String, className: String, text: String): Recognition {
        val pkg = normalizePackage(packageName)
        val cls = className.trim()
        val t = text.trim()
        // 微信/支付宝常把「已收钱」「转账成功」拆到不同节点，合并后中间是换行，
        // 直接 contains("已收钱") 会失败 —— 同时准备一份去空白的紧凑文本。
        val compact = t.replace("\n", "").replace("\r", "")
            .replace(" ", "").replace("\u00A0", "").replace("\u3000", "")

        // 0) 聊天页 / 微信主界面：必然出现「微信支付」服务号名与转账消息气泡，成功词不可信
        if (cls.isNotBlank() && CHAT_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }) {
            return Recognition(PaymentScreen.UNKNOWN, "chatPage:$cls", 0f)
        }

        // 0b) 转账中间态：收款方还没点「确认收款」，钱没真正到账，绝不能记账
        val pendingHit = PENDING_TRANSFER.firstOrNull { compact.contains(it) }
        if (pendingHit != null &&
            TRANSFER_IN_HINTS.none { compact.contains(it) } &&
            TRANSFER_OUT_HINTS.none { compact.contains(it) }
        ) {
            return Recognition(
                PaymentScreen.ENTRY, "transferPending:$pendingHit", 0.72f,
                sceneFor(pkg, BillDirection.NEUTRAL, compact), BillDirection.NEUTRAL
            )
        }

        // 1) 红包（发 / 收）：必须「红包上下文 + 收/发信号」同时命中。
        //    只认「已领取」会把微信卡包领券记成支付成功；只认「已存入零钱」会把收红包误当转账。
        redPacketHit(pkg, compact)?.let { return it }

        // 2) 提现 / 充值 / 零钱通·余额宝划转：钱只是换了个账户，记支出会把总支出翻倍
        val withdrawHit = WITHDRAW_HINTS.firstOrNull { compact.contains(it) }
        if (withdrawHit != null && NEGATIVE.none { compact.contains(it) }) {
            return Recognition(
                PaymentScreen.SUCCESS, "internalTransfer:$withdrawHit", 0.86f,
                internalScene(pkg), BillDirection.NEUTRAL
            )
        }

        // 3) 转账转出 / 收款：转出优先（「转账成功 + 已存入对方零钱」是支出）
        transferHit(pkg, compact)?.let { return it }

        // 4) 类名强信号：微信结果页常返回空根节点，此时文本判断完全失效
        if (cls.isNotBlank() && looksLikeResultClass(cls)) {
            val dir = directionOf(compact)
            val scene = when {
                RED_PACKET_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) } ->
                    redPacketScene(pkg, dir)
                cls.contains("Transfer", ignoreCase = true) ||
                    cls.contains("Remittance", ignoreCase = true) -> transferScene(pkg, dir)
                else -> sceneFor(pkg, dir, compact)
            }
            return Recognition(PaymentScreen.SUCCESS, "className:$cls", 0.92f, scene, dir)
        }
        if (cls.isNotBlank() && ENTRY_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }) {
            return Recognition(
                PaymentScreen.ENTRY, "className:$cls", 0.85f,
                sceneFor(pkg, BillDirection.NEUTRAL, compact), BillDirection.NEUTRAL
            )
        }

        // 5) 完成态文案：微信有专属词表；统一用紧凑文本匹配跨行拆分
        val successWords = if (pkg == WECHAT) WECHAT_SUCCESS + COMMON_SUCCESS else COMMON_SUCCESS
        val hit = successWords.firstOrNull { compact.contains(it) }
        if (hit != null) {
            val hasNegative = NEGATIVE.any { compact.contains(it) }
            val dir = directionOf(compact)
            return Recognition(
                PaymentScreen.SUCCESS, "keyword:$hit",
                if (hasNegative) 0.72f else 0.88f,
                sceneFor(pkg, dir, compact), dir
            )
        }

        // 3b) 抖音订单完成页：只有「实付 ¥xx + 订单详情」而没有通用成功词。
        if (pkg == DOUYIN) {
            val hasResultClass = cls.isNotBlank() &&
                DOUYIN_RESULT_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }
            if (hasResultClass) {
                return Recognition(
                    PaymentScreen.SUCCESS, "douyinClass:$cls", 0.88f,
                    PaymentScene.DOUYIN_ORDER, BillDirection.EXPENSE
                )
            }
            val hasOrderContext = DOUYIN_ORDER_CONTEXT.any { compact.contains(it) }
            val dHit = DOUYIN_SUCCESS_HINTS.firstOrNull { compact.contains(it) }
            if (dHit != null && hasOrderContext && NEGATIVE.none { compact.contains(it) }) {
                return Recognition(
                    PaymentScreen.SUCCESS, "douyin:$dHit+订单上下文", 0.86f,
                    PaymentScene.DOUYIN_ORDER, BillDirection.EXPENSE
                )
            }
        }

        // 4) 账单 / 消费记录详情：提交捕获（由 CaptureCoordinator 走去重 → 轻提醒），
        //    必须排在 ENTRY 前，否则「付款方式」会抢先命中。
        //    强字段（交易单号等）直接认；弱词（消费记录等）要求金额行少，避免把列表页当一笔。
        val strongHit = DETAIL_STRONG.firstOrNull { compact.contains(it) }
        if (strongHit != null) {
            return Recognition(
                PaymentScreen.DETAIL, "keyword:$strongHit", 0.66f,
                billDetailScene(pkg), BillDirection.EXPENSE
            )
        }
        val weakHit = DETAIL_WEAK.firstOrNull { compact.contains(it) }
        if (weakHit != null) {
            val moneyLines = amountLineCount(t)
            if (moneyLines in 1..3) {
                return Recognition(
                    PaymentScreen.DETAIL, "keyword:$weakHit+金额行$moneyLines", 0.55f,
                    billDetailScene(pkg), BillDirection.EXPENSE
                )
            }
        }

        // 5) 付款界面（中间态）：明确识别，但绝不触发记账
        val entryHit = ENTRY_TEXT.firstOrNull { compact.contains(it) }
        if (entryHit != null) {
            return Recognition(
                PaymentScreen.ENTRY, "keyword:$entryHit", 0.70f,
                sceneFor(pkg, BillDirection.NEUTRAL, compact), BillDirection.NEUTRAL
            )
        }
        return Recognition(PaymentScreen.UNKNOWN, "none", 0f)
    }

    /** 转出优先：只有「转账成功 + 已存入对方零钱」这种组合，绝不能落到收入。 */
    private fun directionOf(compact: String): BillDirection {
        if (TRANSFER_OUT_HINTS.any { compact.contains(it) }) return BillDirection.EXPENSE
        if (TRANSFER_IN_HINTS.any { compact.contains(it) }) return BillDirection.INCOME
        return BillDirection.EXPENSE
    }

    /**
     * 红包判定（v1.3.8）：红包上下文 + 收/发信号**双条件**。
     * 返回 null 表示「不像红包」，交回转账 / 通用逻辑继续判断。
     */
    private fun redPacketHit(pkg: String, compact: String): Recognition? {
        if (RED_PACKET_CONTEXT.none { compact.contains(it) }) return null
        val out = RED_PACKET_OUT_HINTS.firstOrNull { compact.contains(it) }
        if (out != null) {
            return Recognition(
                PaymentScreen.SUCCESS, "redPacketOut:$out", 0.93f,
                redPacketScene(pkg, BillDirection.EXPENSE), BillDirection.EXPENSE
            )
        }
        val income = RED_PACKET_IN_HINTS.firstOrNull { compact.contains(it) }
        if (income != null) {
            return Recognition(
                PaymentScreen.SUCCESS, "redPacketIn:$income", 0.93f,
                redPacketScene(pkg, BillDirection.INCOME), BillDirection.INCOME
            )
        }
        return null
    }

    /** 转账判定（v1.3.8）：先转出后收款，两个方向都落到明确场景。 */
    private fun transferHit(pkg: String, compact: String): Recognition? {
        val out = TRANSFER_OUT_HINTS.firstOrNull { compact.contains(it) }
        if (out != null) {
            return Recognition(
                PaymentScreen.SUCCESS, "transferOut:$out", 0.92f,
                transferScene(pkg, BillDirection.EXPENSE), BillDirection.EXPENSE
            )
        }
        val income = TRANSFER_IN_HINTS.firstOrNull { compact.contains(it) }
        if (income != null) {
            return Recognition(
                PaymentScreen.SUCCESS, "transferIn:$income", 0.92f,
                transferScene(pkg, BillDirection.INCOME), BillDirection.INCOME
            )
        }
        return null
    }

    private fun redPacketScene(pkg: String, direction: BillDirection): PaymentScene = when (pkg) {
        WECHAT -> if (direction == BillDirection.INCOME) PaymentScene.WECHAT_RED_PACKET_IN
        else PaymentScene.WECHAT_RED_PACKET_OUT
        ALIPAY -> if (direction == BillDirection.INCOME) PaymentScene.ALIPAY_RED_PACKET_IN
        else PaymentScene.ALIPAY_RED_PACKET_OUT
        else -> PaymentScene.UNKNOWN
    }

    private fun transferScene(pkg: String, direction: BillDirection): PaymentScene = when (pkg) {
        WECHAT -> if (direction == BillDirection.INCOME) PaymentScene.WECHAT_TRANSFER_IN
        else PaymentScene.WECHAT_TRANSFER_OUT
        ALIPAY -> if (direction == BillDirection.INCOME) PaymentScene.ALIPAY_TRANSFER_IN
        else PaymentScene.ALIPAY_TRANSFER_OUT
        UNIONPAY -> PaymentScene.UNIONPAY_PAY
        else -> PaymentScene.BANK_TRANSFER
    }

    private fun internalScene(pkg: String): PaymentScene = when (pkg) {
        WECHAT -> PaymentScene.WECHAT_WITHDRAW
        ALIPAY -> PaymentScene.ALIPAY_WITHDRAW
        else -> PaymentScene.INTERNAL_TRANSFER
    }

    private fun sceneFor(pkg: String, direction: BillDirection, compact: String): PaymentScene = when {
        pkg == WECHAT && RED_PACKET_CONTEXT.any { compact.contains(it) } ->
            if (direction == BillDirection.INCOME) PaymentScene.WECHAT_RED_PACKET_IN
            else PaymentScene.WECHAT_RED_PACKET_OUT
        pkg == WECHAT && direction == BillDirection.INCOME -> PaymentScene.WECHAT_TRANSFER_IN
        pkg == WECHAT && (compact.contains("转账") || compact.contains("对方零钱")) ->
            PaymentScene.WECHAT_TRANSFER_OUT
        pkg == WECHAT -> PaymentScene.WECHAT_MERCHANT_PAY
        pkg == ALIPAY && direction == BillDirection.INCOME -> PaymentScene.ALIPAY_TRANSFER_IN
        pkg == ALIPAY && compact.contains("转账") -> PaymentScene.ALIPAY_TRANSFER_OUT
        pkg == ALIPAY -> PaymentScene.ALIPAY_MERCHANT_PAY
        pkg == UNIONPAY -> PaymentScene.UNIONPAY_PAY
        pkg == DOUYIN -> PaymentScene.DOUYIN_ORDER
        pkg.isBlank() -> PaymentScene.UNKNOWN
        else -> PaymentScene.BANK_TRANSFER
    }

    /**
     * v1.3.11：抽取「这笔交易本身的时间」。
     *
     * 用户反馈：转账记录这类页面应该按**记录时间**判断是否重复。
     * 同一笔账单反复打开，页面上的「支付时间 / 转账时间 / 收款时间」是固定的，
     * 用它做账单时间和去重指纹，重复识屏会被判重；不同笔即使金额相同，时间也不同，不会被误挡。
     *
     * 页面没有时间字段时返回空串，调用方退回「识屏时刻」。
     */
    fun transactionTimeKey(text: String): String {
        if (text.isBlank()) return ""
        val rawLines = text.lines()
        // 1) 优先带标签的行：支付时间 / 转账时间 / 收款时间 / 交易时间…
        for (line in rawLines) {
            val t = line.trim()
            if (TIME_LABEL_TOKENS.none { t.contains(it) }) continue
            val token = TIME_TOKEN.find(t)?.value?.trim().orEmpty()
            if (token.isNotBlank()) return token
        }
        // 2) 退化为整页第一个日期时间：仍是页面上的真实时间，比识屏时刻可靠
        for (line in rawLines) {
            val token = TIME_TOKEN.find(line.trim())?.value?.trim().orEmpty()
            if (token.isNotBlank()) return token
        }
        return ""
    }

    /**
     * 场景 → 备注前缀（v1.3.8）。
     *
     * 红包页抽到的「商户」往往是「恭喜发财，大吉大利」这种祝福语，
     * 直接当备注会得到一笔看不懂的账。用「微信红包 · 张三」这种前缀，
     * 既说清钱从哪来，又保留对方昵称。
     */
    fun remarkPrefix(scene: PaymentScene): String = when (scene) {
        PaymentScene.WECHAT_TRANSFER_OUT -> "微信转账"
        PaymentScene.WECHAT_TRANSFER_IN -> "微信收款"
        PaymentScene.WECHAT_RED_PACKET_OUT -> "微信红包"
        PaymentScene.WECHAT_RED_PACKET_IN -> "微信红包"
        PaymentScene.ALIPAY_TRANSFER_OUT -> "支付宝转账"
        PaymentScene.ALIPAY_TRANSFER_IN -> "支付宝收款"
        PaymentScene.ALIPAY_RED_PACKET_OUT -> "支付宝红包"
        PaymentScene.ALIPAY_RED_PACKET_IN -> "支付宝红包"
        else -> ""
    }

    private fun billDetailScene(pkg: String): PaymentScene = when (pkg) {
        WECHAT -> PaymentScene.WECHAT_BILL_DETAIL
        ALIPAY -> PaymentScene.ALIPAY_BILL_DETAIL
        DOUYIN -> PaymentScene.DOUYIN_ORDER
        else -> PaymentScene.UNKNOWN
    }

    /**
     * 微信结果页 / 账单详情页节点结构适配。
     *
     * 微信「支付成功」页常把金额和商户放在成功文案下面的独立节点里，且没有标签，
     * FieldExtractor / PaymentTextAnalyzer 的 `金额：` / `商户：` 标签命中不到。
     * 这里在原文后面追加两行带标签的结构化文本（不删原行），让既有抽取器直接命中。
     *
     * v1.3.1：
     * - 扫描范围从「成功词后 6 行」扩到「成功词后 12 行 / 无成功词时全页前 40 行」，
     *   覆盖账单详情页把金额放在页面中部、底部的情况；
     * - 只在「有成功词」或「有详情词」时精炼，收银台等中间态保持原文不变；
     * - 已有标签行时不重复追加。
     *
     * 全程不用正则：项目约定正则一律走 SafeRegex.of，且 Android 17 ICU 对字符类里的 \s 会抛异常。
     */
    fun refineWeChatText(text: String): String {
        if (text.isBlank()) return text
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val successIndex = lines.indexOfFirst { line ->
            (WECHAT_SUCCESS + COMMON_SUCCESS).any { line.contains(it) }
        }
        val isResult = successIndex >= 0
        if (!isResult && !isDetailText(text)) return text

        val window = if (isResult) lines.drop(successIndex + 1).take(12) else lines.take(40)
        // v1.3.9：金额行跳过余额 / 优惠 / 积分 / 退款等非实付行，
        // 旧实现会把「优惠 -0.50」当成金额，导致账单金额错得离谱。
        val amountLine = window.firstOrNull { line ->
            looksLikeAmount(line) && NON_PAYMENT_LINE_HINTS.none { line.contains(it) }
        }
        // v1.3.9：商户优先从明确标签取（同行值 或 标签下一行）。
        // 微信账单详情里「商户全称」「收单机构」各占一行，旧实现猜「第一行文字」，
        // 抽到的往往是收单机构「财付通支付科技有限公司」——这就是用户看到的错误商户名。
        // 页面上存在收单机构字段时，干脆不猜：宁可这条不补，也不写错商户。
        val hasAcquirerLine = lines.any { line -> ACQUIRER_LABELS.any { line.contains(it) } }
        val merchantLine = extractLabelledMerchant(lines)
            ?: if (hasAcquirerLine) null else guessMerchantLine(window, amountLine)
        val hasAmountLabel = lines.any {
            it.startsWith("金额：") || it.startsWith("实付") || it.startsWith("付款金额") ||
                it.startsWith("支付金额") || it.startsWith("交易金额")
        }
        val hasMerchantLabel = lines.any {
            it.startsWith("商户：") || it.startsWith("收款方") || it.startsWith("商户名称")
        }
        if ((amountLine == null || hasAmountLabel) && (merchantLine == null || hasMerchantLabel)) {
            return text
        }

        return buildString {
            append(text)
            if (!text.endsWith('\n')) append('\n')
            if (amountLine != null && !hasAmountLabel) {
                append("金额：")
                    .append(cleanAmount(amountLine))
                    .append('\n')
            }
            if (merchantLine != null && !hasMerchantLabel) {
                append("商户：").append(merchantLine).append('\n')
            }
        }
    }

    /** 从「标签：值」或「标签一行、值下一行」取商户；取不到返回 null。 */
    private fun extractLabelledMerchant(lines: List<String>): String? {
        for ((index, line) in lines.withIndex()) {
            val label = MERCHANT_VALUE_LABELS.firstOrNull { line.startsWith(it) } ?: continue
            val inline = line.removePrefix(label).trim().trim('：', ':', ' ')
            if (isMerchantValue(inline)) return inline
            // v1.3.10：「商户全称」被拆成「商户」+「全称」时 inline 是残片，
            // 旧实现会原样返回「全称」。现在继续向下找，最多看 3 行；
            // 遇到非残片又不合法的行（如「收单机构」）立即停，避免越界取到通道方。
            for (offset in 1..3) {
                val value = lines.getOrNull(index + offset)?.trim().orEmpty()
                if (value.isEmpty()) continue
                if (value.trim('：', ':') in MERCHANT_LABEL_FRAGMENTS) continue
                if (isMerchantValue(value)) return value
                break
            }
        }
        return null
    }

    private fun isMerchantValue(value: String): Boolean =
        value.length in 2..24 &&
            value.trim('：', ':') !in MERCHANT_LABEL_FRAGMENTS &&
            !looksLikeAmount(value) &&
            value !in MERCHANT_VALUE_LABELS &&
            value !in NOISE_LINES &&
            ACQUIRER_LABELS.none { value.contains(it) } &&
            NON_PAYMENT_LINE_HINTS.none { value.contains(it) }

    /** 无标签时的兜底猜测（保留旧行为，但排除收单机构与非实付行）。 */
    private fun guessMerchantLine(window: List<String>, amountLine: String?): String? =
        window.firstOrNull { line ->
            line != amountLine && !NOISE_LINES.any { line.contains(it) } &&
                !looksLikeAmount(line) && line.length in 2..20 &&
                ACQUIRER_LABELS.none { line.contains(it) } &&
                NON_PAYMENT_LINE_HINTS.none { line.contains(it) } &&
                line.any { it.code > 0x7F || it.isLetter() }
        }

    private fun cleanAmount(line: String): String = line.trim()
        .removePrefix("¥").removePrefix("￥")
        .removePrefix("-").removePrefix("−")
        .removeSuffix("元").trim()

    /**
     * 抖音订单页适配（v1.3.1）：抖音把金额写在「实付 ¥xx」行、商户写在「商家 / 店铺」行，
     * 既没有「¥xx」独立行也没有「商户：」标签，抽取器会漏掉。这里补两行结构化文本。
     * 只处理订单现场，收银台等中间态保持原文。
     */
    fun refineDouyinText(text: String): String {
        if (text.isBlank()) return text
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // 只处理订单现场：收银台也会写「实付 ¥xx」，不能因为一个金额标签就补结构化行。
        // 必须有「订单详情 / 交易详情」或「实付 + 订单上下文」才补。
        val isOrderPage = lines.any { isDetailText(it) } ||
            (lines.any { line -> DOUYIN_SUCCESS_HINTS.any { line.contains(it) } } &&
                DOUYIN_ORDER_CONTEXT.any { ctx -> lines.any { it.contains(ctx) } })
        if (!isOrderPage) return text

        val amountLine = lines.firstOrNull { line ->
            (line.contains("实付") || line.contains("支付金额") || line.contains("订单金额") ||
                line.contains("合计")) && line.any { it.isDigit() }
        }
        val amountValue = amountLine?.let { line ->
            val start = line.indexOfFirst { it.isDigit() }
            if (start < 0) null else {
                var end = start
                while (end < line.length && (
                        line[end].isDigit() || line[end] == '.' || line[end] == ','
                    )) end++
                line.substring(start, end).takeIf { it.any { it.isDigit() } }
            }
        }
        val merchantLine = lines.firstOrNull { line ->
            (line.startsWith("商家") || line.startsWith("店铺") || line.startsWith("商户") ||
                line.startsWith("收款方")) && line.length in 2..24 && line !in NOISE_LINES
        }
        val merchantValue = merchantLine?.let { line ->
            line.substringAfter("：").trim().takeIf { it.isNotBlank() && it != line }
                ?: line.substringAfter(":").trim().takeIf { it.isNotBlank() && it != line }
                ?: line.removePrefix("商家").removePrefix("店铺").removePrefix("商户")
                    .removePrefix("收款方").trim().trim('：', ':')
        }?.takeIf { it.length in 2..24 && it !in NOISE_LINES }

        val hasAmountLabel = lines.any { it.startsWith("金额：") || it.startsWith("实付金额") }
        val hasMerchantLabel = lines.any { it.startsWith("商户：") }
        if ((amountValue == null || hasAmountLabel) && (merchantValue == null || hasMerchantLabel)) {
            return text
        }
        return buildString {
            append(text)
            if (!text.endsWith('\n')) append('\n')
            if (amountValue != null && !hasAmountLabel) append("金额：").append(amountValue).append('\n')
            if (merchantValue != null && !hasMerchantLabel) append("商户：").append(merchantValue).append('\n')
        }
    }

    /** 纯字符判断，零正则：`¥23.00` / `￥23` / `23.00元` / `1,280.00` 均视为金额行。 */
    private fun looksLikeAmount(line: String): Boolean {
        val s = cleanAmount(line)
        if (s.isEmpty() || !s.any { it.isDigit() }) return false
        if (s.count { it == '.' } > 1) return false
        return s.all { it.isDigit() || it == '.' || it == ',' }
    }
}
