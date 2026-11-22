package com.azu.bookkeeper.automation.classify

import javax.inject.Inject
import javax.inject.Singleton

/** 业务标签：仅内存态，不落新列；抖音命中不改 captureType（增量架构 §3.1）。 */
enum class BusinessTag { NONE, DOUYIN }

/** 抖音免密支付三路打标：读屏/通知靠包名，短信靠关键词。 */
@Singleton
class DouyinBusinessTagger @Inject constructor() {

    fun tag(packageName: String, text: String): BusinessTag {
        if (packageName == DOUYIN_PACKAGE) return BusinessTag.DOUYIN
        if (KEYWORDS.any { text.contains(it, ignoreCase = true) }) return BusinessTag.DOUYIN
        return BusinessTag.NONE
    }

    companion object {
        const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        val KEYWORDS = listOf("抖音", "抖音支付", "抖音商城", "巨量引擎", "douyin", "aweme")
    }
}
