package com.azu.bookkeeper.ui.importflow.template

import com.azu.bookkeeper.domain.model.ImportPresets

/** UI 侧预设展示元数据；映射与别名事实源在 domain.model.ImportPresets。 */
data class PresetTemplateItem(
    val sourceKey: String,
    val name: String,
    val description: String
)

object PresetTemplates {

    val ALL: List<PresetTemplateItem> = ImportPresets.ALL.map {
        PresetTemplateItem(it.sourceKey, it.name, it.description)
    }

    val CUSTOM: PresetTemplateItem = PresetTemplateItem(
        sourceKey = ImportPresets.CUSTOM_KEY,
        name = "自定义文件",
        description = "手动映射每一列，可保存为自定义模板"
    )

    fun displayName(sourceKey: String): String =
        (ALL + CUSTOM).firstOrNull { it.sourceKey == sourceKey }?.name ?: sourceKey
}
