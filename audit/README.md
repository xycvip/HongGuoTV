# 热更新脚本修复与替换交付

本目录的 `HongGuoTV.js` 是交付版脚本，替换 `xycvip/RedGuoAPI` 的 `dist/hongguoTV/HongGuoTV.js` 后生效。本次包含两项改动：**榜单加载慢修复** + **手机验证码接口全面替换为官方 App 通道**。

---

## 一、榜单加载慢 — 修复（已完成）

**根因**：榜单分页游标 `states` 只存在 JS 运行时内存里。每次脚本调用新建 QuickJS runtime → 游标归零 → 翻第 N 页要重放 N 次请求，越翻越慢；再叠加首屏自动补第 2 页，一进页面就是 3～4 次串行请求。

**脚本改动**：榜单闭包新增宿主 KV 持久化，`loadRankState`/`saveRankState` 把 `state`（`pages`+`cursors`）序列化写进 `ranking:state:v1:{榜单id}`（TTL 2 小时），`paged()` 每页保存后写回。游标跨 runtime 存活，翻第 2 页不再重放。旧宿主无 KV 时自动退回内存，行为不变。

**本仓库 Kotlin 改动**（已编译通过）：
- `HongguoJsContent.kt`：`rankingPage` 用 `callCached(3min)` 再兜一层缓存。
- `AppViewModel.kt`：删除首屏立刻补第 2 页。
- `JsRuntimeThreads.kt`：runtime 并发 2→4、线程 4→6。

---

## 二、手机验证码 → 官方 App 通道（本次新增）

**来源**：完整移植 `OOIUa/hongguo-android` 的 `AppPhoneLogin` + 签名栈（`Signer.kt`/`AesV3.kt`/`Bytes.kt`/`VendorConstants.kt`）。

**为什么换**：原实现走网页通道 `/passport/web/send_code/`（mix 异或），现在平台要求滑块验证，基本不可用；App 通道走 `/passport/mobile/*`，带官方签名，无需滑块。

**改动内容**：
- 内联整套签名栈（MD5/SM3/AesV3/gorgon/helios/medusa，纯 JS）。**用目标仓库 12 个 golden 向量逐字节校验，branch/hash/gorgon/helios/medusa 全部 60/60 通过**。
- `send`：`GET /passport/mobile/send_code/v1/`，取 `mobile_ticket` + `retry_time`，维护冷却。
- `verify`：按 `sms_login → sms_login_only → sms_login_continue → validate_code` 顺序尝试，每个 endpoint 更新 ticket，直到拿到 `sessionid` 登录态；错误码映射成友好文案，不外泄供应商原始内容。
- 在 `accountCall` 顶部拦截 `phoneCode`/`phoneSms` 走新通道；返回契约（`_cookies`/`_device`/`retryAfter`/`mobile`/`rejected`）保持不变，**Kotlin 侧无需改动**。

**验证**：
- 12 golden 向量 60/60 逐字节一致。
- 端到端 mock：send→verify 全流程、签名头齐全、ticket 链式传递、多端点回退、sessionid 落库。
- 边界：滑块拦截、冷却、验证码格式、错误码 1102 可重试、重复验证码拒绝、原始内容不外泄（6/6）。
- 内联后再次从交付版提取代码回归：MD5/SM3/签名头/send/verify 全通过。

---

## 三、清单字段（与当前 HongGuoTV.js 一致）

| 字段 | 值 |
|---|---|
| version | 2026.11.27.1218 |
| size | 572534 |
| sha256 | 1c0b4afa6089f729819cce9608b59471e7e525da2b318b97b230a92f70bb7245 |
| builtAt | 2026-11-27T12:18+08:00 |

---

## 四、发布步骤

1. 把 `HongGuoTV.js` 覆盖到 `xycvip/RedGuoAPI` 的 `dist/hongguoTV/HongGuoTV.js`。
2. 把 `index.json` 同步到 `dist/index.json`（可直接用本目录文件）。
3. 提交并推送 `main`。CI 若重算 sha256，以 CI 产出为准，但务必保证脚本内容与清单一致。
4. App 下次热更新拉取新脚本后生效。

> 校验：`sha256sum HongGuoTV.js` 必须等于 `index.json` 的 `sha256`，否则 App 拒绝安装。本目录两个文件已保证一致（1c0b4afa…b7245）。
