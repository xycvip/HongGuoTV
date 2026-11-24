// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import java.io.File

/**
 * QuickJS 字节码缓存：key = 脚本内容 sha256 + 引擎版本。
 * 字节码与引擎版本强绑定，换版本必须重新编译；磁盘缓存失败不影响运行（退回内存）。
 */
class JsBytecodeCache(private val dir: File? = null) {
    private val memory = LinkedHashMap<String, ByteArray>()
    private val lock = Any()

    fun get(key: String): ByteArray? = synchronized(lock) {
        memory[key]?.let { return it }
        val file = dir?.let { File(it, fileName(key)) } ?: return null
        if (!file.isFile) return null
        runCatching { file.readBytes() }.getOrNull()?.also { memory[key] = it }
    }

    fun put(key: String, bytecode: ByteArray) {
        synchronized(lock) {
            memory[key] = bytecode
            while (memory.size > MAX_MEMORY_ENTRIES) {
                val oldest = memory.keys.firstOrNull() ?: break
                memory.remove(oldest)
            }
            val target = dir ?: return
            runCatching {
                target.mkdirs()
                val temp = File(target, fileName(key) + ".tmp")
                temp.writeBytes(bytecode)
                if (!temp.renameTo(File(target, fileName(key)))) {
                    temp.delete()
                }
                prune(target)
            }
        }
    }

    /**
     * 热更新每换一版脚本就会产生一个新 key，磁盘上的 .qbc 会一直堆积（单份约 1MB）。
     * 只保留最近 [MAX_DISK_ENTRIES] 份，旧的自然滚掉。
     */
    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".qbc") } ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_DISK_ENTRIES)
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun fileName(key: String): String = "main-$key.qbc"

    companion object {
        private const val MAX_MEMORY_ENTRIES = 4
        private const val MAX_DISK_ENTRIES = 4

        fun memoryOnly(): JsBytecodeCache = JsBytecodeCache()

        /** 磁盘缓存放在 filesDir 下；失败（只读/空间不足）由 put 内部吞掉。 */
        fun onDisk(baseDir: File): JsBytecodeCache {
            val dir = File(baseDir, "js-bytecode")
            runCatching { dir.mkdirs() }
            return JsBytecodeCache(if (dir.isDirectory) dir else null)
        }
    }
}
