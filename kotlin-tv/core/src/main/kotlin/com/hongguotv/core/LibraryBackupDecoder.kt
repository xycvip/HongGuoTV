// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 备份 JSON 解码与输入校验。
 *
 * 将复杂的导入校验与 LibraryBackup 的编码、合并职责分开；
 * 对外仍通过 LibraryBackup.decode 保持原有调用接口。
 */
internal object LibraryBackupDecoder {
    private val idPattern = Regex("[0-9]{1,30}")
    private fun id(value: String) = value.also { require(idPattern.matches(it)) { "剧集标识无效" } }
    private fun cleanCover(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase()
        value.takeIf {
            it.length <= 2048 && uri.scheme == "https" && uri.userInfo == null &&
                host.isNotEmpty() && !host.contains(':') && !host.matches(Regex("[0-9.]+")) &&
                host != "localhost" && !host.endsWith(".local") && !host.endsWith(".lan") &&
                value.none(Char::isISOControl)
        }.orEmpty()
    }.getOrDefault("")

    private fun JSONObject.number(key: String,max: Long): Long {
        val value=get(key); require(value is Number) { "数值字段无效" }
        val decimal=value.toDouble(); val integer=value.toLong()
        require(decimal.isFinite() && decimal==integer.toDouble() && integer in 0..max) { "数值范围无效" }; return integer
    }
    private fun JSONObject.string(key: String,max: Int): String {
        val value=get(key); require(value is String && value.length<=max && value.none(Char::isISOControl)) { "文字字段无效" }; return value
    }
    private fun JSONObject.rows(key: String,max: Int): List<JSONObject> {
        val array=getJSONArray(key); require(array.length()<=max) { "记录数量超过上限" }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun series(o: JSONObject): Series = Series(id(o.string("id",30)),o.string("title",200).also { require(it.isNotBlank()) },cleanCover(o.string("cover",2048)),"",o.string("badge",100),o.string("tags",200))
    fun decode(value: String): BackupData {
        // 用户可能用记事本/Notepad++ 改过后另存为 UTF-8 with BOM，剥离 BOM 再解析避免误判「备份格式无效」
        val v=value.trim().removePrefix("\uFEFF")
        require(v.toByteArray(Charsets.UTF_8).size<=LibraryBackup.MAX_BYTES) { "备份超过 2 MB 限制" }
        var depth=0; var quoted=false; var escaped=false
        v.forEach { c ->
            if(escaped) escaped=false
            else if(quoted && c=='\\') escaped=true
            else if(c=='"') quoted=!quoted
            else if(!quoted) { if(c=='{' || c=='[') { depth++; require(depth<=12) { "备份结构过深" } }; if(c=='}' || c==']') depth-- }
        }
        val o=JSONObject(v)
        require(o.getString("format")=="hongguotv.library" && o.number("version",1)==1L) { "不支持的备份格式或版本" }
        val later=if(o.has("later")) o.rows("later",100).map(::series) else emptyList()
        require(later.distinctBy { it.id }.size==later.size) { "稍后看包含重复剧集" }
        val hidden=if(o.has("hidden")) o.getJSONArray("hidden") else JSONArray()
        require(hidden.length()<=500)
        val hiddenIds=(0 until hidden.length()).map { id(hidden.getString(it)) }.toSet()
        val favorites=o.rows("favorites",500).map(::series)
        val groupsJson=o.optJSONObject("favoriteGroups")
        val favoriteGroups=mutableMapOf<String,String>()
        if(groupsJson!=null) {
            require(groupsJson.length()<=500) { "分组数量超过上限" }
            groupsJson.keys().forEach { key ->
                val name=groupsJson.get(key)
                require(name is String && name.isNotBlank() && name.length<=20 && name.none(Char::isISOControl)) { "分组名称无效" }
                favoriteGroups[id(key)]=name
            }
        }
        val history=o.rows("history",200).map { row ->
            val duration=row.number("duration",604_800_000)
            BackupProgress(series(row.getJSONObject("series")),id(row.string("episodeId",30)),row.number("episodeIndex",100_000).toInt(),row.number("position",604_800_000).let { if(duration>0) minOf(it,duration) else it },duration,row.get("completed").also { require(it is Boolean) } as Boolean,row.number("updatedAt",4_102_444_800_000))
        }
        require(favorites.distinctBy { it.id }.size==favorites.size && history.distinctBy { it.series.id }.size==history.size) { "备份包含重复剧集" }
        val watched=o.getJSONArray("watched"); require(watched.length()<=500)
        val watchedIds=(0 until watched.length()).map { id(watched.getString(it)) }.toSet()
        val searches=o.rows("searches",20).map { row -> RecentSearch(row.string("query",80).also { require(it.isNotBlank()) },ContentType.entries.firstOrNull { it.storedValue==row.getString("type") } ?: error("搜索类型无效")) }
        val settings=o.getJSONObject("settings")
        val quality=settings.number("quality",PlaybackQuality.AUTO_COMPAT).toInt(); require(quality in PlaybackQuality.options)
        val speed=settings.get("speed"); require(speed is Number && speed.toFloat() in PlaybackSpeed.options)
        val autoNext=settings.get("autoNext"); require(autoNext is Boolean)
        val type=ContentType.entries.firstOrNull { it.storedValue==settings.getString("type") } ?: error("内容类型无效")
        val frame=VideoFrameMode.entries.firstOrNull { it.name==settings.getString("frame") } ?: error("画面模式无效")
        val autoSeason=settings.optBoolean("autoSeason",true)
        val danmaku=settings.optBoolean("danmaku",true)
        val danmakuOpacity=DanmakuPrefs.opacity(settings.optInt("danmakuOpacity",DanmakuPrefs.OPACITY_DEFAULT))
        val danmakuFont=DanmakuPrefs.font(settings.optInt("danmakuFont",DanmakuPrefs.FONT_DEFAULT))
        val danmakuSpeed=DanmakuPrefs.speed(settings.optInt("danmakuSpeed",DanmakuPrefs.SPEED_DEFAULT))
        val danmakuRows=DanmakuPrefs.rows(settings.optInt("danmakuRows",DanmakuPrefs.ROWS_DEFAULT))
        val showClock=settings.optBoolean("showClock",false)
        // 旧备份没有该字段时按默认「显示」处理
        val sourceHidden=settings.optBoolean("sourceHidden",false)
        val startupAutoplay=settings.optBoolean("startupAutoplay",true)
        val sleepActionMode=settings.optInt("sleepActionMode",0).also { require(it in 0..1) { "定时关闭方式无效" } }
        val theme=settings.optString("theme","NIGHT").also { require(it in listOf("NIGHT","PORCELAIN")) { "系统风格无效" } }
        val videoInfoMode=settings.optInt("videoInfoMode",0).also { require(it in 0..2) { "播放信息显示模式无效" } }
        val showNetworkSpeed=settings.optBoolean("showNetworkSpeed",false)
        // 三方源：旧备份没有该字段时静默为空；非法接口直接跳过，不影响整体恢复
        val thirdSources=if(o.has("thirdSources")) o.rows("thirdSources",ThirdSourceConfig.MAX).mapNotNull { row ->
            val api=runCatching { row.string("api",500) }.getOrNull() ?: return@mapNotNull null
            if(!ThirdSourceConfig.validApi(api)) return@mapNotNull null
            val name=runCatching { row.string("name",40) }.getOrDefault("")
                .ifBlank { api.removePrefix("https://").removePrefix("http://").substringBefore('/') }
            BackupThirdSource(name,api)
        }.distinctBy { it.api } else emptyList()
        // 三方观看历史：缺字段、类型异常或单条损坏都只影响这一段，绝不拖垮整份备份
        val thirdHistory: List<BackupThirdHistory> = if(o.has("thirdHistory")) runCatching {
            val rows=o.optJSONArray("thirdHistory") ?: return@runCatching emptyList<BackupThirdHistory>()
            (0 until minOf(rows.length(),LibraryBackup.MAX_THIRD_HISTORY)).mapNotNull { index ->
                runCatching {
                    val row=rows.optJSONObject(index) ?: return@runCatching null
                    val api=row.optString("sourceApi").take(500)
                    val videoId=row.optString("videoId").take(80)
                    if(!ThirdSourceConfig.validApi(api) || videoId.isBlank()) null else BackupThirdHistory(
                        api,row.optString("sourceName").take(40),videoId,row.optString("title").take(200),
                        row.optString("cover").take(2048),
                        row.optInt("episodeIndex",0).coerceIn(0,100_000),row.optString("episodeLabel").take(60),
                        row.optLong("positionMs",0).coerceIn(0,604_800_000),row.optLong("durationMs",0).coerceIn(0,604_800_000),
                        row.optLong("updatedAt",0).coerceIn(0,4_102_444_800_000),row.optBoolean("completed",false))
                }.getOrNull()
            }
        }.getOrDefault(emptyList()) else emptyList()
        // 三方收藏：缺字段、类型异常或单条损坏都只影响这一段，绝不拖垮整份备份
        val thirdFavorites: List<BackupThirdFavorite> = if(o.has("thirdFavorites")) runCatching {
            val rows=o.optJSONArray("thirdFavorites") ?: return@runCatching emptyList<BackupThirdFavorite>()
            (0 until minOf(rows.length(),LibraryBackup.MAX_THIRD_FAVORITES)).mapNotNull { index ->
                runCatching {
                    val row=rows.optJSONObject(index) ?: return@runCatching null
                    val api=row.optString("sourceApi").take(500)
                    val videoId=row.optString("videoId").take(80)
                    if(!ThirdSourceConfig.validApi(api) || videoId.isBlank()) null else BackupThirdFavorite(
                        api,row.optString("sourceName").take(40),videoId,row.optString("title").take(200),
                        row.optString("cover").take(2048),
                        row.optInt("episodeIndex",0).coerceIn(0,100_000),row.optString("episodeLabel").take(60),
                        row.optLong("positionMs",0).coerceIn(0,604_800_000),row.optLong("updatedAt",0).coerceIn(0,4_102_444_800_000))
                }.getOrNull()
            }
        }.getOrDefault(emptyList()) else emptyList()
        val accountState = if (o.has("accountState") && !o.isNull("accountState")) {
            val account = o.getJSONObject("accountState")
            require(account.toString().length <= 20_000) { "账户备份信息过大" }
            account.toString()
        } else null
        val playbackExtras = if (o.has("playbackExtras") && !o.isNull("playbackExtras")) {
            val extra = o.getJSONObject("playbackExtras")
            val blockedArray = extra.optJSONArray("blockedWords") ?: JSONArray()
            require(blockedArray.length() <= 200) { "屏蔽词数量超过上限" }
            val blockedWords = (0 until blockedArray.length()).map { index ->
                val word = blockedArray.get(index)
                require(word is String && word.length <= 50 && word.isNotBlank() && word.none(Char::isISOControl)) { "屏蔽词格式无效" }
                word
            }.toSet()
            val perSeriesJson = extra.optJSONObject("perSeriesDanmaku") ?: JSONObject()
            require(perSeriesJson.length() <= 500) { "按剧弹幕设置数量超过上限" }
            val perSeriesDanmaku = mutableMapOf<String, Boolean>()
            perSeriesJson.keys().forEach { key ->
                require(idPattern.matches(key)) { "按剧弹幕剧集标识无效" }
                val enabled = perSeriesJson.get(key)
                require(enabled is Boolean) { "按剧弹幕设置无效" }
                perSeriesDanmaku[key] = enabled
            }
            fun bool(key: String, fallback: Boolean): Boolean {
                if (!extra.has(key)) return fallback
                val value = extra.get(key)
                require(value is Boolean) { "播放增强设置无效：$key" }
                return value
            }
            fun stringValue(key: String, fallback: String): String {
                if (!extra.has(key)) return fallback
                val value = extra.get(key)
                require(value is String && value.length <= 30) { "播放增强设置无效：$key" }
                return value
            }
            val seekStep = if (extra.has("seekStepSeconds")) extra.number("seekStepSeconds", 60).toInt() else 10
            require(seekStep in listOf(10, 15, 30, 60)) { "快进步长无效" }
            val speedValue = extra.opt("longPressSpeed")
            val longPressSpeed = if (speedValue == null) 3f else {
                require(speedValue is Number && speedValue.toFloat() in listOf(2f, 3f, 4f)) { "长按快进倍速无效" }
                speedValue.toFloat()
            }
            val menuKeyAction = stringValue("menuKeyAction", "panel")
            require(menuKeyAction in listOf("panel", "episodes", "danmaku")) { "菜单键行为无效" }
            val remoteActions = listOf("previous", "next", "menu", "comments", "play_pause", "episodes", "none")
            val remoteUpAction = stringValue("remoteUpAction", "previous")
            val remoteDownAction = stringValue("remoteDownAction", "next")
            val remoteConfirmAction = stringValue("remoteConfirmAction", "play_pause")
            val remoteMenuAction = stringValue("remoteMenuAction", "menu")
            require(listOf(remoteUpAction, remoteDownAction, remoteConfirmAction, remoteMenuAction).all { it in remoteActions }) { "遥控器按键行为无效" }
            val skipMarks = SkipMarks.decode(extra.optJSONObject("skipMarks")?.toString())
            BackupPlaybackExtras(
                blockedWords, perSeriesDanmaku, bool("autoSkipUnplayable", false), seekStep,
                longPressSpeed, menuKeyAction, remoteUpAction, remoteDownAction,
                remoteConfirmAction, remoteMenuAction, skipMarks
            )
        } else null
        return BackupData(favorites,history,watchedIds,searches,BackupSettings(quality,speed.toFloat(),autoNext,type,frame,autoSeason,danmaku,danmakuOpacity,danmakuFont,danmakuSpeed,danmakuRows,showClock,sourceHidden,startupAutoplay,sleepActionMode,theme,videoInfoMode,showNetworkSpeed),later,hiddenIds,favoriteGroups,thirdSources,thirdHistory,thirdFavorites,accountState,playbackExtras)
    }
}
