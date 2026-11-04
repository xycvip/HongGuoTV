// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * TVBox 导入规划器。
 *
 * 只负责把候选站点转换成“新增项 + 统计文案”，不读写存储、不探活、不操作 UI。
 * 同一批候选中的重复 API 也会被识别；源数量达到上限后，其余新源计入超限数。
 */
internal object ThirdSourceImportPlanner {

    internal data class Plan(
        val added: List<ThirdSource>,
        val importedCount: Int,
        val duplicateCount: Int,
        val overflowCount: Int
    ) {
        val message: String
            get() = buildString {
                append("成功导入 $importedCount 个源")
                if(duplicateCount > 0) append("，跳过重复 $duplicateCount 个")
                if(overflowCount > 0) append("，超出上限跳过 $overflowCount 个")
            }
    }

    fun plan(
        existing: List<ThirdSource>,
        candidates: List<ThirdTvboxSite>,
        maxSources: Int
    ): Plan {
        val knownApis = existing.mapTo(HashSet()) { it.api }
        var remaining = (maxSources - existing.size).coerceAtLeast(0)
        var duplicates = 0
        var overflow = 0
        val added = mutableListOf<ThirdSource>()

        candidates.forEach { site ->
            when {
                site.api in knownApis -> duplicates++
                remaining <= 0 -> overflow++
                else -> {
                    knownApis += site.api
                    val id = "u" + site.api.hashCode().toUInt().toString()
                    added += ThirdSource(id, site.name, site.api)
                    remaining--
                }
            }
        }

        return Plan(
            added = added,
            importedCount = added.size,
            duplicateCount = duplicates,
            overflowCount = overflow
        )
    }
}
