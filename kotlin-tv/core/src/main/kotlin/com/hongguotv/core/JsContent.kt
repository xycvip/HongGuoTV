// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

/**
 * 内容接口的 JS 门面（热更新脚本 main.js）。
 *
 * core 是纯 JVM 模块，不能依赖 Android 侧的 playback-js，因此这里只定义接口；
 * 由 App 启动时（AppViewModel.init）把 JS 实现装进 [JsContent.gateway]，
 * 仓库类只面向接口编程。迁移完成后原生实现已删除，门面缺失等同于接线故障，
 * 直接抛错而不是静默退回不存在的旧数据源。
 *
 * 接口是阻塞式的：JS 调用在实现内部 runBlocking 落定（与 HongguoJsStreamSource
 * 同一模式），仓库方法保持迁移前的同步签名，调用方无需感知协程。
 */
interface JsContentGateway {
    /** 首页/分类目录。[type] 决定走官方发现接口的哪个分类。 */
    fun home(page: Int, type: ContentType): CatalogPage

    /**
     * 搜索。[type] 为 null 时是官方统一搜索（综合结果）；
     * 短剧/漫剧由官方响应里的 content_type 过滤（1004=短剧，1=漫剧）。
     */
    fun search(keyword: String, page: Int, type: ContentType?): CatalogPage

    /** 搜索联想词。 */
    fun suggest(query: String): List<String>

    /** 剧集详情：系列信息 + 分集 videoId 列表。 */
    fun detail(id: String): Detail

    /** 同名义的其他季（官方多季数据），不含当前剧集。 */
    fun seasons(sourceId: String): List<Series>

    /** 官方榜单分页；[board] 决定榜单分组，数据来自官方榜单接口。 */
    fun rankingPage(board: RankBoard, page: Int): CatalogPage

    /** 弹幕一个 30 秒窗口；由 DanmakuLoader 在播放推进时调用。 */
    fun danmakuWindow(seriesId: String, vid: String, durationMs: Long, startMs: Long): DanmakuWindow

    /**
     * 剧院筛选 + 剧集分页（官方 landpage 发现接口，V5 签名）。
     * [type] 为 null 表示综合，否则取 ContentType.name；[selected] 为筛选条件。
     * 会话游标由脚本内部维护，调用方只传页码。
     */
    fun theater(page: Int, type: ContentType?, selected: Map<String, String>): TheaterCatalogPage

    /** 剧院筛选面板（第 1 页），供题材选择器。 */
    fun theaterFilters(type: ContentType): List<TheaterFilterGroup>
}

/** 进程内门面注册表。 */
object JsContent {
    @Volatile
    var gateway: JsContentGateway? = null

    fun required(): JsContentGateway =
        gateway ?: error("热更新内容门面未安装：App 启动时应安装 HongguoJsContent")
}

/** 弹幕一个时间窗的评论与下一窗起点（原 DanmakuRepository.Window，随仓库接口化移出）。 */
data class DanmakuWindow(val comments: List<Danmaku>, val nextStartMs: Long)

/** 弹幕窗口数据源：原生 HTTP 实现已删，现由 playback-js 的 JS 门面提供。 */
interface DanmakuWindowSource {
    @Throws(IOException::class)
    fun window(seriesId: String, vid: String, durationMs: Long, startMs: Long): DanmakuWindow
}
