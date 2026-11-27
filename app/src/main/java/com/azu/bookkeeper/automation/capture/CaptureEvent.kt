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
    val screenType: String = "UNKNOWN"
)
