// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * DNS 服务商。国内三条线路都带两台服务器，前一台超时自动换下一台；
 * 「系统」不接管解析，完全交给 Android 的 netd。
 */
enum class DnsProvider(
    val key: String,
    val label: String,
    val hint: String,
    val servers: List<String>,
) {
    SYSTEM("system", "系统", "跟随系统", emptyList()),
    TENCENT("tencent", "腾讯 DNS", "国内最快", listOf("119.29.29.29", "182.254.116.116")),
    ALI("ali", "阿里 DNS", "国内多线", listOf("223.5.5.5", "223.6.6.6")),
    GOOGLE("google", "谷歌 DNS", "海外线路", listOf("8.8.8.8", "8.8.4.4")),
    CUSTOM("custom", "自定义", "手动填写", emptyList());

    companion object {
        fun of(key: String?): DnsProvider = entries.firstOrNull { it.key == key } ?: TENCENT
    }
}

/**
 * 全局 DNS 配置。设置页改完立即生效：OkHttp 的 dns 回调每次都读这里，
 * 不需要重建 OkHttpClient（重建会丢掉已建立的连接池）。
 */
object DnsSettings {
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    @Volatile
    private var provider: DnsProvider = DnsProvider.TENCENT

    @Volatile
    private var custom: List<String> = emptyList()

    fun provider(): DnsProvider = provider

    fun customText(): String = custom.joinToString(", ")

    /** 应用设置；非法 IP 会被丢弃，返回被丢弃的条目数供界面提示。 */
    fun update(providerKey: String?, customText: String?): Int {
        val next = DnsProvider.of(providerKey)
        val parsed = parseServers(customText)
        val rawCount = (customText ?: "").split(',', '，', ';', '；', ' ', '\n')
            .map { it.trim() }
            .count { it.isNotEmpty() }
        provider = next
        custom = parsed
        NetworkTuning.clearDnsCache()
        return (rawCount - parsed.size).coerceAtLeast(0)
    }

    /** 当前生效的服务器列表；空 = 交给系统。 */
    fun activeServers(): List<String> = when (provider) {
        DnsProvider.SYSTEM -> emptyList()
        DnsProvider.CUSTOM -> custom
        else -> provider.servers
    }

    fun activeLabel(): String = when (provider) {
        DnsProvider.SYSTEM -> "系统"
        DnsProvider.CUSTOM -> if (custom.isEmpty()) "自定义（未填写）" else "自定义 · ${custom.joinToString(" / ")}"
        else -> "${provider.label} · ${provider.servers.first()}"
    }

    fun describe(): String = when (provider) {
        DnsProvider.SYSTEM -> "由系统网络解析"
        DnsProvider.CUSTOM -> if (custom.isEmpty()) "未填写服务器，暂用系统解析" else custom.joinToString(", ")
        else -> provider.servers.joinToString(", ")
    }

    fun parseServers(text: String?): List<String> =
        (text ?: "").split(',', '，', ';', '；', ' ', '\n')
            .map { it.trim() }
            .filter { isIpv4(it) }
            .distinct()

    fun isIpv4(value: String): Boolean {
        if (!IPV4.matches(value)) return false
        return value.split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
    }
}

/**
 * 带 TTL 缓存的 DNS 解析器。
 *
 * OkHttp 的 Dns 是同步接口（调用发生在 OkHttp 自己的 IO 线程），这里直接发一条
 * UDP A 查询，不走系统的 getaddrinfo——后者在部分电视盒子上会因为 IPv6 回退
 * 多等 2~4 秒，是「首页/搜索偶尔要转很久」的常见原因。
 *
 * 公共 DNS 不可达时（企业网只放行系统 DNS）自动回退 Dns.SYSTEM，不会把网络打死。
 */
class SmartDns : Dns {
    private class CacheEntry(val addresses: List<InetAddress>, val expireAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    /** 每个域名一把锁：不同域名的解析互不排队（启动预热与用户搜索会同时发生）。 */
    private val locks = ConcurrentHashMap<String, Any>()

    override fun lookup(hostname: String): List<InetAddress> {
        val servers = DnsSettings.activeServers()
        if (servers.isEmpty()) return Dns.SYSTEM.lookup(hostname)
        // IP 字面量没有解析的必要，也避免把 "1.2.3.4" 当成域名发查询。
        if (DnsSettings.isIpv4(hostname)) return Dns.SYSTEM.lookup(hostname)

        cache[hostname]?.let { entry ->
            if (entry.expireAt > System.currentTimeMillis()) return entry.addresses
        }
        val hostLock = locks.getOrPut(hostname) { Any() }
        synchronized(hostLock) {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { entry ->
                if (entry.expireAt > now) return entry.addresses
            }
            // 两台服务器并行问，谁先给出结果用谁的：单台被网络策略挡住时不会白等一轮。
            val addresses = queryAny(hostname, servers)
            if (addresses.isNotEmpty()) {
                cache[hostname] = CacheEntry(addresses, now + CACHE_TTL_MS)
                return addresses
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    fun clear() = cache.clear()

    private fun queryAny(host: String, servers: List<String>): List<InetAddress> {
        val tasks = servers.map { server ->
            java.util.concurrent.Callable { queryA(host, server, QUERY_TIMEOUT_MS) }
        }
        if (tasks.isEmpty()) return emptyList()
        return runCatching {
            resolver.invokeAny(tasks, QUERY_TIMEOUT_MS + 300L, TimeUnit.MILLISECONDS)
        }.getOrNull().orEmpty()
    }

    private fun queryA(host: String, server: String, timeoutMs: Int): List<InetAddress> {
        val query = buildQuery(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val target = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(target, 53)))
            val buffer = ByteArray(1_500)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return parseAnswers(host, buffer, packet.length)
        }
    }

    private fun buildQuery(host: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeShort(Random.nextInt(1, 0xFFFF))
            data.writeShort(0x0100) // RD：期望递归
            data.writeShort(1)      // QDCOUNT
            data.writeShort(0)      // ANCOUNT
            data.writeShort(0)      // NSCOUNT
            data.writeShort(0)      // ARCOUNT
            host.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                data.writeByte(bytes.size)
                data.write(bytes)
            }
            data.writeByte(0)
            data.writeShort(1) // A
            data.writeShort(1) // IN
        }
        return out.toByteArray()
    }

    private fun parseAnswers(host: String, buffer: ByteArray, length: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val input = DataInputStream(ByteArrayInputStream(buffer, 0, length))
        input.readUnsignedShort() // id
        val flags = input.readUnsignedShort()
        if (flags and 0x8000 == 0) return emptyList() // 不是响应
        if (flags and 0x000F != 0) return emptyList()  // NXDOMAIN / SERVFAIL
        val questions = input.readUnsignedShort()
        val answers = input.readUnsignedShort()
        repeat(questions) {
            skipName(input)
            input.readUnsignedShort() // type
            input.readUnsignedShort() // class
        }
        val result = ArrayList<InetAddress>(answers.coerceAtMost(8))
        repeat(answers) {
            skipName(input)
            val type = input.readUnsignedShort()
            input.readUnsignedShort() // class
            input.readInt()           // ttl
            val dataLength = input.readUnsignedShort()
            if (type == 1 && dataLength == 4) {
                val ip = ByteArray(4)
                input.readFully(ip)
                result += InetAddress.getByAddress(host, ip)
            } else {
                input.skipBytes(dataLength)
            }
        }
        return result
    }

    private fun skipName(input: DataInputStream) {
        while (true) {
            val length = input.readUnsignedByte()
            when {
                length == 0 -> return
                length and 0xC0 == 0xC0 -> {
                    input.readUnsignedByte() // 压缩指针的第二字节
                    return
                }
                else -> input.skipBytes(length)
            }
        }
    }

    companion object {
        private const val QUERY_TIMEOUT_MS = 800
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        // Process-wide DNS workers are shared by every Activity/ViewModel. Let idle
        // workers time out instead of retaining four thread stacks for the whole process.
        private val resolver = java.util.concurrent.ThreadPoolExecutor(
            4,
            4,
            30,
            TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
        ) { runnable ->
            Thread(runnable, "hongguo-dns").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.apply { allowCoreThreadTimeOut(true) }
    }
}

/**
 * 全局网络参数。整个进程共用一套连接池，接口与播放地址共享 TCP/TLS 连接，
 * 避免每类请求各建一条长连接。
 */
object NetworkTuning {
    /** 数据接口的域名：启动后预热一次 DNS + TLS，首次搜索/联想/播放少一次握手。 */
    val API_HOSTS = listOf(
        "hongguoduanju.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "reading.snssdk.com",
        "api5-normal.fqnovel.com",
    )

    private val dns = SmartDns()

    fun client(): OkHttpClient = OkHttpClient.Builder()
        // 连接阶段超时压到 8 秒：坏线路尽快失败，交给上层换镜像/重试，而不是干等 15 秒。
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .dns(dns)
        .retryOnConnectionFailure(true)
        .build()

    fun clearDnsCache() = dns.clear()

    /** 启动后预热接口域名：只做 DNS 解析，不额外打请求，也不会和首屏抢连接。 */
    fun warmDns() {
        API_HOSTS.forEach { host -> runCatching { dns.lookup(host) } }
    }

    /**
     * 打开详情页时预热播放域名：一次 HEAD 把 TLS 握手留在共享连接池里，
     * 用户看完简介点播放时少一次 1~2 个 RTT。必须在 IO 线程调用。
     */
    fun warmPlayback(client: OkHttpClient) {
        val quick = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
        PLAY_HOSTS.forEach { host ->
            runCatching { dns.lookup(host) }
            runCatching {
                quick.newCall(Request.Builder().url("https://$host/").head().build()).execute().close()
            }
        }
    }

    private val PLAY_HOSTS = listOf(
        "reading.snssdk.com",
        "api5-normal-sinfonlineb.fqnovel.com",
    )
}
