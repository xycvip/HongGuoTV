// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.json.JSONArray
import org.json.JSONObject

/** 三方片源列表 JSON 编解码，负责数据格式转换，不持有存储状态。 */
internal object ThirdStoreCodec {
    fun readSources(raw: String?): List<ThirdSource> {
        raw ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id"); val api = item.optString("api")
            if(id.isEmpty() || api.isEmpty()) null
            else ThirdSource(id, item.optString("name"), api)
        }
    }

    fun writeSources(list: List<ThirdSource>): String {
        val array = JSONArray()
        list.forEach { source ->
            array.put(JSONObject().put("id", source.id).put("name", source.name).put("api", source.api))
        }
        return array.toString()
    }
}
