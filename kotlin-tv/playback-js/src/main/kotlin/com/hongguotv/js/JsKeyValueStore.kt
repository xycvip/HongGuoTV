// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import android.content.Context

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

/**
 * 落盘实现：SharedPreferences 异步写（apply），进程重启后仍保留。
 *
 * 用途：设备身份这类需要「注册一次、长期复用」的数据——既省掉每次切标签的
 * device_register 往返（延迟），又让云端看到的是一台稳定设备而非反复出现的新设备（风控）。
 *
 * 值约定：value 为 null 表示删除；ttlMillis=null / <=0 表示永久有效。
 * 为兼容 host.cacheSet 的语义，过期时间写入同一文件，get 时惰性判活。
 */
class SharedPrefsJsKeyValueStore(context: Context, name: String = "hongguo-js-kv") : JsKeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        val expiresAt = prefs.getLong(expiryKey(key), 0L)
        if (expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            prefs.edit().remove(key).remove(expiryKey(key)).apply()
            return null
        }
        return raw
    }

    override fun set(key: String, value: String?, ttlMillis: Long?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key).remove(expiryKey(key)).apply()
            return
        }
        val expiresAt = ttlMillis?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it } ?: 0L
        editor.putString(key, value)
        if (expiresAt > 0L) editor.putLong(expiryKey(key), expiresAt)
        else editor.remove(expiryKey(key))
        editor.apply()
    }

    private fun expiryKey(key: String) = "${key}__expiry"
}
