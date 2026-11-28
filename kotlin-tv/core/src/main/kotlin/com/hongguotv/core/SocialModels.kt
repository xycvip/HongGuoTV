// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 详情页「发布作者 / 剧评」社交数据模型。
 *
 * 请求构造、签名、响应解析全部在热更新脚本的 accountCall/accountParse 内完成，
 * 本模块只承载宿主侧归一化后的不可变数据。这批接口走匿名设备签名，
 * 未登录也能读取（带登录态时内容更全），因此不强制 [AccountCredentials]。
 */

/**
 * 发布作者（红果里的创作者 / 达人号）。
 *
 * [id] 为纯数字用户编号，是 creatorProfile / creatorWorks 的入参；
 * [relativeType] 本应用口径：3 = 发布作者本人（主创作者 / 发布方 / 制作方）。
 * 接口里的热门演职员（演员）已在热更新脚本侧排除，不会进入作者列表；
 * [following]/[canFollow] 仅在带登录态时才有意义，匿名调用时为 null/false。
 */
data class Creator(
    val id: String,
    val name: String = "",
    val avatar: String = "",
    val description: String = "",
    val worksCount: Int = 0,
    val following: Boolean? = null,
    val canFollow: Boolean = false,
    val relativeType: Int = 0,
) {
    val worksCapable: Boolean get() = id.matches(Regex("[0-9]{1,30}"))
}

/** 作者档案（creatorProfile 返回，含关注状态）。 */
data class CreatorProfile(val creator: Creator)

/** 作者作品分页：游标语义同演员作品，[nextCursor] 原样回传。 */
data class CreatorWorksPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val nextCursor: String,
    val nextOffset: Int,
)

/** 一条剧评。 */
data class Review(
    val id: String,
    val text: String,
    val userName: String,
    val userAvatar: String,
    val userId: String,
    val createTime: Long,
    val diggCount: Int,
    val replyCount: Int,
)

/** 剧评分页。 */
data class ReviewPage(
    val items: List<Review>,
    val total: Int,
    val hasMore: Boolean,
    val cursor: String,
)
