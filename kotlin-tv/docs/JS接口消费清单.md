# 热更新 JS 接口消费清单

> 迁移完成后，App 的**全部**数据来源统一为 `main.js`（热更新脚本），包括剧院与所有签名。
> 原生数据接口、网站抓取、设备注册与签名模块（`playback-native`）已全部删除。
> 本清单列出 main.js 对外导出的全部能力，标注 App 是否消费。

## 一、main.js 导出总览（30 个）

| # | 导出函数 | 用途 | App 是否消费 | 消费入口 |
|---|---|---|---|---|
| 1 | `meta` | 脚本版本/来源诊断 | ✅ | 诊断信息 |
| 2 | `categories` | 分类元数据 | ⚠️ 间接 | `validCategory`/`catalogPage` 内部用 |
| 3 | `validCategory` | 分类键校验 | ⚠️ 内部 | JS 内部调用 |
| 4 | `catalogPage` | 首页/分类分页 | ✅ | `HongguoJsContent.home` |
| 5 | `search` | 搜索 | ✅ | `HongguoJsContent.search` |
| 6 | `suggestions` | 搜索联想 | ✅ | `HongguoJsContent.suggest` |
| 7 | `detail` | 详情+分集 | ✅ | `HongguoJsContent.detail` |
| 8 | `seasons` | 同剧多季 | ✅ | `HongguoJsContent.seasons` |
| 9 | `resolveMedia` | 播放直链+密钥 | ✅ | `HongguoJsStreamSource` |
| 10 | `rankingBoards` | 榜单分组列表 | ✅ | `HongguoJsContent.rankingPage` |
| 11 | `rankingPage` | 榜单分页 | ✅ | `HongguoJsContent.rankingPage` |
| 12 | `danmaku` | 弹幕时间窗 | ✅ | `HongguoJsContent.danmakuWindow` |
| 13 | `accountCall` | 账号统一入口（登录+云端数据） | ✅ | `HongguoJsAccount` 全部方法 |
| 14 | `resetAccount` | 退出登录清会话 | ✅ | `HongguoJsAccount.signOut` |
| 15 | `currentCookie` | 读取当前 cookie | ⚠️ 诊断用 | 未直接调 |
| 16 | `actorRanking` | 演员榜分页 | ✅ | `HongguoJsActorRank`（P2a 迁移） |
| 17 | `actorWorks` | 演员参演作品 | ✅ | `HongguoJsActorRank`（P2a 迁移） |
| 18 | `theater` | 剧院筛选+剧集分页 | ✅ | `HongguoJsContent.theater`（最终迁移） |
| 19 | `theaterFilters` | 剧院筛选面板 | ✅ | `HongguoJsContent.theaterFilters` |
| 21 | `recommendations` | 相关推荐 | ❌ **未消费** | App 详情页未接 |
| 22 | `validRecommendation` | 推荐项校验 | ❌ **未消费** | 随 recommendations |
| 23 | `feed` | 信息流推荐 | ✅ | `HongguoJsContent.feed`（启动自动播放的选剧第一层） |
| 24 | `danmakuIds` | 弹幕 ID 列表（旧接口） | ❌ **未消费** | 统一走 danmaku |
| 25 | `devices` | 设备伪装列表 | ⚠️ 内部 | JS 内部调用（含剧院设备注册） |
| 26 | `coverAddress` | 封面地址规范化 | ⚠️ 内部 | JS 内部调用 |
| 27 | `needsExtraMetadata` | 是否需补充元数据 | ❌ **未消费** | 门面未用 |
| 28 | `extraMetadata` | 补充元数据 | ❌ **未消费** | 门面未用 |
| 29 | `catalogState` | 分类游标状态 | ⚠️ 内部 | JS 内部翻页用 |
| 30 | `restoreCatalogState` | 恢复分类游标 | ❌ **未消费** | 门面未用 |
| 31 | `account` / `accountParse` / `setAccountActive` | 账号低层能力 | ✅ 间接 | `accountCall` 内部封装 |

## 二、App 未消费的 JS 导出（明确回答你的问题）

以下 JS 导出 App 侧没有调用：

| 导出 | 说明 | 影响 |
|---|---|---|
| `recommendations` + `validRecommendation` | 相关作品推荐。详情页 `detail` 返回的 `related` 已被门面置空，未走此接口 | 详情页无"相关推荐"区块 |
| `danmakuIds` | 旧版弹幕 ID 列表接口 | 无影响，弹幕已统一走 `danmaku` |
| `needsExtraMetadata` + `extraMetadata` | 封面/元数据的二次补全 | 无影响，详情接口已自带元数据 |
| `restoreCatalogState` | 恢复分类翻页游标 | 无影响，门面每次从首页重新拉 |

> 这些是脚本提供了但 App 暂时没用上的"储备能力"，删除原生接口不影响它们。如果以后要做相关推荐/信息流，直接接即可。

## 三、仍走原生/网站、未切 JS 的接口

**无。** 数据接口已 100% 切到 JS：

| 原接口 | 最终去向 |
|---|---|
| `theater` / `theaterFilters`（剧院） | ✅ 已迁入 `main.js`（`src/theater.js`，V5 签名打官方 landpage 接口，实测 code=0） |
| `genres`（网站题材选择器） | ✅ 已删除——筛选面板由 JS `theater` 第 1 页返回，App 不再需要网站题材 |
| 设备注册 + V5 签名 | ✅ 已删除——vendor 内部 `Ot()` 自助注册、`Ht()` V5 签名，一次性覆盖内容+播放+剧院 |

> 原生 `playback-native` 模块（Signer / DeviceRegister / PlaybackDeviceSession）已整体删除，
> `settings.gradle.kts` 及 core/app 依赖同步移除。签名材料只在 JS 仓库的 `signing.json`（gitignore），
> 由 `build.py` 注入 `main.js`，App 端不再有任何签名代码。

## 四、已删除的原生接口实现

- 播放线路：`playback-phoenix` 整个模块；原生播放类全部移除
- 内容接口：`DetailRepository`、`SearchRepository`、`RelatedWorks`、`ComicRank`、`SearchRanking`
- 弹幕：`DanmakuRepository`
- 账号：`WebLogin`、`PhoneLogin`、`CloudAccountClient`、`CloudAccountTransport`、`CloudAccountParsers`
- 演员榜：`ActorRankParser`、`ActorWorksParser`
- **剧院：`TheaterRepository`（V4 medusa 签名）→ JS V5 签名替代**
- **签名/设备：`playback-native` 整个模块（Signer / DeviceRegister / PlaybackDeviceSession）**
- **网站抓取：`SearchRepository.genres`、`MemoryCookies`、`extractRouter`**
- 其他：`OfficialCommentRepository`（无人消费）、`Probe` CLI

## 五、验证结果

- `main.js` 语法检查通过；`theater` / `theaterFilters` 真实接口探针返回 code=0、筛选面板+剧集齐全
- `:core` / `:playback-js` / `:app` 全量编译通过；`:app:assembleDebug` 成功产出 APK，内置脚本含 theater.js + V5 签名
- playback-js 冒烟测试 **17/17 通过**（原 15 + 剧院 2；真实接口偶发抖动，已内置 3 次重试）
- core 测试 124/125 通过；唯一失败 `PhoneSearchServerTest` 为会话开始前就存在的工作区历史遗留
  （`PhoneSearchServer.kt` 有 11-23 的既有未提交改动，本次未碰；失败断言是本地 HTML 页面内容，与数据接口/签名无关）
- 剧院会话跨调用持久化：会话状态写入 `host.cacheGet/cacheSet`，解决"每次调用新建 QuickJS 实例导致内存态丢失"的问题
