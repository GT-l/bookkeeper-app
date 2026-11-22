package com.azu.bookkeeper.automation.classify

import com.azu.bookkeeper.data.local.db.dao.RuleDao
import com.azu.bookkeeper.data.local.db.entity.CategoryRuleEntity
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.core.getOrNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抖音种子规则数据（增量架构 §3.2）：priority=80~100 高于通用词，
 * categoryId 在注入时指向「购物」默认分类（用户可在分类管理页改一行数据即可自定义）。
 */
object SeedRules {

    /** 业务识别 + 分类词（categoryId 由注入器回填为抖音默认分类）。 */
    val DOUYIN_CLASSIFY_KEYWORDS = listOf(
        "抖音支付", "抖音商城", "抖音", "巨量引擎", "douyin", "aweme"
    )

    /** 完成态辅助词：priority=90，categoryId=0 表示仅提升"支付已发生"判定，不直接分类。 */
    val COMPLETION_KEYWORDS = listOf(
        "支付成功", "已支付", "付款成功", "交易成功", "订单号", "支付完成"
    )

    /** 字段抽取辅助词：priority=80。 */
    val FIELD_KEYWORDS = listOf("收款方", "商户名称", "订单金额", "扣款金额")

    /** 通用商家词示例（priority=10~50），真实项目可由分类管理页持续补充。 */
    val GENERAL_KEYWORDS: List<Pair<String, Int>> = listOf(
        "星巴克" to 30, "瑞幸" to 30, "肯德基" to 30, "麦当劳" to 30,
        "京东" to 40, "淘宝" to 40, "天猫" to 40, "拼多多" to 40,
        "美团" to 35, "饿了么" to 35, "滴滴" to 35, "高德" to 30,
        "加油站" to 20, "超市" to 20, "便利店" to 20, "电影院" to 20
    )
}

/**
 * 首次启动幂等注入种子规则（增量架构 §3.2 / 5.1）：
 * 仅在关键词不存在时插入，绝不覆盖用户改动。
 */
@Singleton
class SeedRuleInitializer @Inject constructor(
    private val ruleDao: RuleDao,
    private val settings: SettingsRepository,
    private val categoryRepository: CategoryRepository,
    private val automationSettings: AutomationSettingsDataStore
) {

    suspend fun initialize() {
        val existing = ruleDao.getAll().map { it.keyword }.toSet()
        val bookId = settings.defaultBookId.first().takeIf { it > 0L } ?: return
        val configuredId = automationSettings.douyinDefaultCategoryId.first().takeIf { it > 0L }
        val shoppingId = configuredId
            ?: categoryRepository.findByName(bookId, com.azu.bookkeeper.domain.model.CategoryKind.EXPENSE, "购物")
                .getOrNull()?.categoryId
            ?: categoryRepository.matchByName(bookId, com.azu.bookkeeper.domain.model.CategoryKind.EXPENSE, "购物")
                .getOrNull()?.categoryId
            ?: 0L
        if (configuredId == null && shoppingId > 0L) {
            automationSettings.setDouyinDefaultCategoryId(shoppingId)
        }

        val seeds = mutableListOf<CategoryRuleEntity>()
        SeedRules.DOUYIN_CLASSIFY_KEYWORDS.forEach { keyword ->
            if (keyword !in existing) {
                seeds += CategoryRuleEntity(
                    keyword = keyword,
                    categoryId = shoppingId,
                    priority = 100,
                    enabled = true
                )
            }
        }
        SeedRules.COMPLETION_KEYWORDS.forEach { keyword ->
            if (keyword !in existing) {
                seeds += CategoryRuleEntity(keyword = keyword, categoryId = 0L, priority = 90)
            }
        }
        SeedRules.FIELD_KEYWORDS.forEach { keyword ->
            if (keyword !in existing) {
                seeds += CategoryRuleEntity(keyword = keyword, categoryId = 0L, priority = 80)
            }
        }
        SeedRules.GENERAL_KEYWORDS.forEach { (keyword, priority) ->
            if (keyword !in existing) {
                seeds += CategoryRuleEntity(keyword = keyword, categoryId = 0L, priority = priority)
            }
        }
        if (seeds.isNotEmpty()) ruleDao.insertAll(seeds)
    }
}
