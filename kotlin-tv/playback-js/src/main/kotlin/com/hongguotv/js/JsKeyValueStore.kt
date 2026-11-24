// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

/**
 * 脚本侧 KV 缓存（bridge 的 host.cacheGet / host.cacheSet）。
 * 会话 Cookie、榜单缓存、游标都由脚本自己管，宿主只负责持久化。
 */
interface JsKeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?, ttlMillis: Long?)
}

/** 进程内实现：单测与无 Context 场景使用，不持久化。 */
class InMemoryJsKeyValueStore : JsKeyValueStore {
    private data class Entry(val value: String, val expiresAt: Long?)
    private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    override fun get(key: String): String? {
        val entry = map[key] ?: return null
        val expiresAt = entry.expiresAt
        if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    override fun set(key: String, value: String?, ttlMillis: Long?) {
        if (value == null) {
            map.remove(key)
            return
        }
        val expiresAt = ttlMillis?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it }
        map[key] = Entry(value, expiresAt)
    }
}
