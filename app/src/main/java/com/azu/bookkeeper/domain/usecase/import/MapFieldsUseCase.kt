package com.azu.bookkeeper.domain.usecase.import

import com.azu.bookkeeper.domain.model.FieldMapping
import com.azu.bookkeeper.domain.model.ImportField
import com.azu.bookkeeper.domain.model.ImportPresets
import com.azu.bookkeeper.domain.model.ImportTemplate
import javax.inject.Inject

/**
 * 步骤 3：表头 → 系统标准字段映射。
 * 优先级：已选模板显式绑定 → 预设别名精确匹配 → 双向包含模糊匹配 → 跳过。
 */
class MapFieldsUseCase @Inject constructor() {

    operator fun invoke(
        header: List<String>,
        template: ImportTemplate?
    ): FieldMapping {
        val normalizedHeader = header.map { it.trim() }
        val lowerHeader = normalizedHeader.map { it.lowercase() }
        val preset = ImportPresets.byKey(template?.sourceKey.orEmpty())
        val values = mutableMapOf<String, String>()

        ImportField.ALL.forEach { field ->
            val explicit = template?.mapping?.get(field.key).orEmpty()
            val direct = normalizedHeader.firstOrNull { it.equals(explicit, ignoreCase = true) }
            if (!explicit.isBlank() && direct != null) {
                values[field.key] = direct
                return@forEach
            }
            val aliases = preset?.aliases?.get(field.key).orEmpty() + DEFAULT_ALIASES[field.key].orEmpty()
            val aliasHit = aliases.firstNotNullOfOrNull { alias ->
                normalizedHeader.firstOrNull { it.equals(alias, ignoreCase = true) }
            }
            if (aliasHit != null) {
                values[field.key] = aliasHit
                return@forEach
            }
            val fuzzyIndex = aliases.firstNotNullOfOrNull { alias ->
                val lowerAlias = alias.lowercase()
                lowerHeader.indexOfFirst { column ->
                    column.isNotBlank() &&
                        (column.contains(lowerAlias) || lowerAlias.contains(column))
                }.takeIf { it >= 0 }
            }
            values[field.key] = fuzzyIndex?.let { normalizedHeader[it] }.orEmpty()
        }

        val matchedPreset = template?.sourceKey?.takeIf { it.isNotBlank() && it != ImportPresets.CUSTOM_KEY }
            ?: detectSourceKey(header)
        return FieldMapping(values = values, matchedPresetKey = matchedPreset)
    }

    /** 无模板时按表头别名打分，选出最贴近的预设来源。 */
    fun detectSourceKey(header: List<String>): String {
        val normalized = header.map { it.trim().lowercase() }
        var bestKey = ImportPresets.CUSTOM_KEY
        var bestScore = 0
        ImportPresets.ALL.forEach { preset ->
            val score = preset.aliases.values.flatten().sumOf { alias ->
                if (normalized.any { it == alias.lowercase() }) 3
                else if (normalized.any { it.contains(alias.lowercase()) }) 1
                else 0
            }
            if (score > bestScore) {
                bestScore = score
                bestKey = preset.sourceKey
            }
        }
        return bestKey
    }

    private companion object {
        /** 兜底别名：即使用户选了自定义文件也能自动识别常见表头。 */
        val DEFAULT_ALIASES: Map<String, List<String>> = mapOf(
            ImportField.BILL_TIME.key to listOf("交易时间", "交易日期", "日期", "时间", "date"),
            ImportField.AMOUNT.key to listOf("金额(元)", "金额（元）", "交易金额", "金额", "发生额", "amount"),
            ImportField.BILL_TYPE.key to listOf("收支类型", "收/支", "收支", "类型", "交易类型"),
            ImportField.CATEGORY_NAME.key to listOf("分类", "类别", "category"),
            ImportField.ACCOUNT_FROM.key to listOf("账户", "支付方式", "付款账户", "account"),
            ImportField.ACCOUNT_TO.key to listOf("转入账户", "收款账户", "对方账户"),
            ImportField.REMARK.key to listOf("备注", "交易对方", "摘要", "交易摘要", "商品说明", "remark"),
            ImportField.TAG_NAMES.key to listOf("标签", "tags")
        )
    }
}
