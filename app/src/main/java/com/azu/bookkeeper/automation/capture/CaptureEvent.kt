package com.azu.bookkeeper.automation.capture

import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.domain.model.CaptureType

/** 三路捕获（读屏 / 通知 / 短信 / 截屏）统一抽象；eventId 由捕获层生成，保证幂等。 */
data class CaptureEvent(
    val eventId: Long,
    val captureType: CaptureType,
    val rawText: String = "",
    val imagePath: String = "",
    val billTime: Long = 0L,
    val packageName: String = "",
    val businessTag: BusinessTag = BusinessTag.NONE,
    val timeSource: com.azu.bookkeeper.automation.receiver.TimeSource =
        com.azu.bookkeeper.automation.receiver.TimeSource.BODY,
    /** v1.3.0：读屏时的页面类型（ENTRY / SUCCESS / DETAIL / UNKNOWN），仅用于分流与诊断，不落库。 */
    val screenType: String = "UNKNOWN",
    /** v1.3.6：记账方向（EXPENSE / INCOME / NEUTRAL），收款类转账记收入。 */
    val direction: String = "EXPENSE",
    /** v1.3.6：收银台阶段缓存下来的金额（分），成功页字段缺失时补齐。 */
    val pendingAmountFen: Long = 0L,
    /** v1.3.6：收银台阶段缓存下来的商户。 */
    val pendingMerchant: String = ""
)
