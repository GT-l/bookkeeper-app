package com.azu.bookkeeper.automation

import com.azu.bookkeeper.automation.classify.AccountMatcher
import com.azu.bookkeeper.automation.classify.ClassificationRuleEngine
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.data.local.db.dao.RuleDao
import com.azu.bookkeeper.data.local.db.entity.CategoryRuleEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则分类引擎单元测试（种子规则优先级 / 辅助词不得抢占分类）。
 */
class ClassificationEngineTest {

    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val engine = ClassificationRuleEngine(ruleDao, AccountMatcher(), DouyinBusinessTagger())

    /** 模拟 SeedRuleInitializer 注入后的规则表（按 priority DESC 排序由引擎完成）。 */
    private fun seedRules() = listOf(
        CategoryRuleEntity(keyword = "抖音支付", categoryId = 5L, priority = 100),
        CategoryRuleEntity(keyword = "抖音", categoryId = 5L, priority = 100),
        // 完成态辅助词：categoryId=0，只应作为信号，不应被当成「分类命中」
        CategoryRuleEntity(keyword = "支付成功", categoryId = 0L, priority = 90),
        CategoryRuleEntity(keyword = "收款方", categoryId = 0L, priority = 80),
        CategoryRuleEntity(keyword = "商户名称", categoryId = 0L, priority = 80),
        CategoryRuleEntity(keyword = "订单金额", categoryId = 0L, priority = 80),
        CategoryRuleEntity(keyword = "扣款金额", categoryId = 0L, priority = 80),
        CategoryRuleEntity(keyword = "星巴克", categoryId = 7L, priority = 30),
        CategoryRuleEntity(keyword = "超市", categoryId = 8L, priority = 20)
    )

    @Test
    fun `抖音种子规则优先命中并满分`() = runBlocking {
        coEvery { ruleDao.allRules() } returns seedRules()
        val hit = engine.classify("抖音支付", "抖音支付 支付成功")
        assertEquals(5L, hit.categoryId)
        assertTrue(hit.matched)
    }

    @Test
    fun `普通商家词命中分类`() = runBlocking {
        coEvery { ruleDao.allRules() } returns seedRules()
        val hit = engine.classify("星巴克", "今天喝了星巴克")
        assertEquals(7L, hit.categoryId)
        assertTrue(hit.matched)
    }

    @Test
    fun `未命中任何规则时categoryId为0且matched为false`() = runBlocking {
        coEvery { ruleDao.allRules() } returns seedRules()
        val hit = engine.classify("某小店", "某小店消费")
        assertEquals(0L, hit.categoryId)
        assertEquals(false, hit.matched)
    }

    // ---------- 缺陷复现：当前源码预期失败 ----------

    @Test
    fun `BUG复现 完成态辅助词不应抢占真实分类`() = runBlocking {
        coEvery { ruleDao.allRules() } returns seedRules()
        // 文本同时含「支付成功」(priority=90, categoryId=0) 与「星巴克」(priority=30, categoryId=7)。
        // 期望：选中真实分类 7；实际：辅助词优先级更高，categoryId=0 但 matched=true。
        val hit = engine.classify("星巴克支付成功", "支付成功 商户名称：星巴克")
        assertEquals("辅助词不应抢占商家分类（当前错误返回 categoryId=0）", 7L, hit.categoryId)
    }

    @Test
    fun `BUG复现 categoryId为零不应算作分类命中`() = runBlocking {
        coEvery { ruleDao.allRules() } returns seedRules()
        // 仅命中「支付成功」辅助词：无有效分类，matched 必须为 false 才会触发低置信度弹窗。
        val hit = engine.classify("", "支付成功")
        assertEquals(0L, hit.categoryId)
        assertEquals("无分类命中应 matched=false（当前错误返回 true）", false, hit.matched)
    }
}
