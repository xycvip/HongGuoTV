// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 账号能力的 JS 网关契约。
 *
 * 登录（扫码/验证码）和云端数据（资料/收藏/历史/进度/预约）的请求构造、签名、
 * 响应解析全部在热更新脚本 main.js 内完成；宿主实现只负责调脚本并把结果映射成
 * core 模型，以及持久化登录凭证。此接口让 app 层不依赖具体的 JS 实现。
 *
 * 全部方法为阻塞式（内部 runBlocking），与 [JsContentGateway] 一致，方便在
 * Dispatchers.IO 里直接调用。
 */
/** 账号门面注册表：App 启动时安装 JS 实现，业务层通过 required() 取用。 */
object JsAccount {
    @Volatile
    var gateway: JsAccountGateway? = null

    fun required(): JsAccountGateway =
        gateway ?: error("热更新账号门面未安装：App 启动时应安装 HongguoJsAccount")
}

interface JsAccountGateway {
    // ---- 登录会话 ----

    /** 获取扫码二维码；[qrUrl] 为待编码的扫码链接，[expiresIn] 为有效秒数。 */
    fun qr(): QrCode

    /** 轮询扫码状态；true = 用户已确认、登录态已建立。二维码过期时抛异常。 */
    fun poll(): Boolean

    /** 发送手机号验证码，返回脱敏号码与重试秒数。 */
    fun sendPhoneCode(number: String): SentCode

    /** 验证码登录，成功后返回可持久化的登录凭证。 */
    fun verifyPhoneCode(code: String): AccountCredentials

    /** 扫码/验证码确认后，取回登录凭证。 */
    fun credentials(): AccountCredentials

    // ---- 云端数据 ----

    fun profile(credentials: AccountCredentials): CloudProfile
    fun favorites(credentials: AccountCredentials): List<CloudFavorite>
    fun history(credentials: AccountCredentials): List<CloudProgress>
    fun historySeries(credentials: AccountCredentials): List<Series>

    fun setFavorite(credentials: AccountCredentials, seriesId: String, add: Boolean)
    fun uploadProgress(
        credentials: AccountCredentials,
        progress: CloudProgress,
        episodeCount: Int,
    )

    /** 从云端观看历史中软删除某条记录（删除同步：本机移除后推送云端，避免复活）。 */
    fun deleteHistory(credentials: AccountCredentials, seriesId: String, videoId: String, updatedAt: Long)

    fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation>
    fun reserve(credentials: AccountCredentials, work: RelatedWork)

    // ---- 详情页社交数据（匿名设备签名，未登录可读） ----

    /** 按剧集取发布作者 / 演职员列表（creatorList）。 */
    fun creatorList(seriesId: String): List<Creator>

    /** 作者档案（creatorProfile），含关注状态；带登录态时更准。 */
    fun creatorProfile(userId: String, credentials: AccountCredentials? = null): CreatorProfile

    /** 作者作品分页（creatorWorks）；[cursor] 首屏传空，之后原样回传上一页的 nextCursor。 */
    fun creatorWorks(userId: String, cursor: String, count: Int): CreatorWorksPage

    /** 剧评列表（commentList）；[cursor] 首屏传空，之后原样回传上一页的 cursor。 */
    fun commentList(seriesId: String, cursor: String, count: Int): ReviewPage

    /** 关注 / 取关作者（creatorFollow），需要登录态。 */
    fun creatorFollow(userId: String, relativeType: Int, follow: Boolean, credentials: AccountCredentials)

    /** 用持久化凭证恢复会话（把 cookie 注入脚本会话）。 */
    fun restore(credentials: AccountCredentials)

    /** 清除脚本会话内的登录态。 */
    fun signOut()

    data class QrCode(val qrUrl: String, val expiresIn: Int)
    data class SentCode(val maskedMobile: String, val retrySeconds: Int)
}
