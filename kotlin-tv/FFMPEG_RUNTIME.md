# FFmpeg 软件解码运行库接入记录

本分支用于在不改变 main 分支播放器行为的前提下，试验 NextLib / FFmpeg 软件视频解码。

## 当前基线

- 应用：HongGuoTV，Android 原生 Kotlin。
- 最低系统：Android 8.0（API 26）。
- Media3：1.11.1。
- 现有播放链：Media3 ExoPlayer + DefaultRenderersFactory。
- 播放器创建点：主播放器和无缝预加载播放器都需要使用一致的 RenderersFactory 策略。
- 本分支不修改正式版发布工作流，不把 FFmpeg 库打进基础 APK。

## 上游研究基线

- 项目：anilbeesetti/nextlib
- 上游仓库：https://github.com/anilbeesetti/nextlib
- 当前核对版本：v1.11.1-0.17.0
- 与本项目 Media3 1.11.1 版本线匹配。
- 支持 H.264、HEVC、VP8、VP9、AV1 视频解码，以及多种音频解码。
- 上游构建脚本覆盖 armeabi-v7a、arm64-v8a、x86、x86_64。
- 上游常规 Maven AAR 会携带原生库，不满足基础 APK 轻量化和运行时下载目标。

## 已实现

`FfmpegRuntimeInstaller.kt` 提供第一阶段的运行库安装基础：

1. 按 Android 的首选 ABI 顺序选择 32/64 位架构。
2. 只接受 HTTPS 清单和 HTTPS 库文件地址。
3. 下载到应用私有 noBackupFilesDir，不申请公共存储权限。
4. SHA-256 校验每个文件。
5. 检查 ELF 魔数、32/64 位类别和 e_machine 架构。
6. 先写入暂存目录，全部验证通过后再替换目标目录。
7. 网络和文件操作由单线程工作线程执行。

## 清单格式

```json
{
  "version": "0.1.0",
  "abi": "arm64-v8a",
  "libraries": [
    {
      "file": "libavutil.so",
      "url": "https://example.invalid/runtime/arm64-v8a/libavutil.so",
      "sha256": "替换为64位十六进制SHA256",
      "loadOrder": 1
    },
    {
      "file": "libavcodec.so",
      "url": "https://example.invalid/runtime/arm64-v8a/libavcodec.so",
      "sha256": "替换为64位十六进制SHA256",
      "loadOrder": 2
    },
    {
      "file": "libmedia3ext.so",
      "url": "https://example.invalid/runtime/arm64-v8a/libmedia3ext.so",
      "sha256": "替换为64位十六进制SHA256",
      "loadOrder": 3
    }
  ]
}
```

以上仅为格式示例，不是可用下载地址或真实库清单。最终依赖关系和加载顺序必须以实际 ELF DT_NEEDED 检查结果为准。

## 尚未完成（不能误认为已接入）

- 尚未把 NextLib 的 Java/Kotlin 解码器类加入本项目。
- 尚未改造上游 `FfmpegLibrary` 的 `System.loadLibrary()`，使其支持已校验的绝对路径。
- 尚未完成动态加载与 JNI 初始化。
- 尚未把 DecoderManager 接入主播放器和预加载播放器。
- 尚未实现用户可见的软件解码开关或自动回退策略。
- 尚未生成真实 ABI 原生库包及 SHA-256 清单。
- 尚未执行 Android 构建或真机播放测试。

## 后续实施顺序

1. 固定 NextLib 版本并核对其 GPL-3.0 许可证义务。
2. 将 NextLib 的 Media3 解码器 Java/Kotlin 层以可审计方式纳入工程，保留上游版权和许可证。
3. 将原生库构建产物与 APK 分离，生成每个 ABI 的完整依赖包。
4. 改造库加载器，使其只加载校验成功的应用私有目录绝对路径。
5. 主播放器与无缝预加载播放器统一使用 NextRenderersFactory，并分别管理 DecoderManager 生命周期。
6. 保持默认硬件解码；用户明确选择或硬解失败时才启用 FFmpeg 软解。
7. 完成构建、安装、32/64 位、H.264/HEVC、内存和连续播放测试后，再考虑合并。

## 安全要求

- 不从未校验的清单加载原生代码。
- 不允许清单中的路径穿越或任意文件名。
- 仅下载设备当前 ABI 的库。
- 新版本全部下载并验证成功后才替换旧版本。
- 任何下载、校验或加载失败都必须保持现有 MediaCodec 播放路径可用。
