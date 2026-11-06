# FFmpeg 软件解码接入记录

本分支 `feature/ffmpeg-soft-decode` 用于独立试验 FFmpeg 软件解码；不修改 `main`，不改变正式版发布链路。

## 项目基线

- 应用：HongGuoTV，Kotlin / Android。
- 最低系统：Android 8.0（API 26）。
- Media3：1.11.1。
- FFmpeg：9.0.1。
- NextLib 解码器源码基线：`anilbeesetti/nextlib` v`1.11.1-0.17.0`。
- ABI：`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。
- NextLib 上游许可：GPL-3.0；相关源码和许可证保留在 `kotlin-tv/third_party/nextlib`。

## 当前已经接入的代码

1. 将 NextLib 的 Media3 FFmpeg 解码器 Java/Kotlin 源码纳入 app 源集，未把 FFmpeg 原生库打进基础 APK。
2. 修改 `FfmpegLibrary`，支持从经过校验的应用私有目录按绝对路径加载 FFmpeg 依赖和 JNI 桥接库。
3. `FfmpegRuntimeInstaller` 支持按当前应用进程 ABI 选择运行库、HTTPS 下载、SHA-256 校验、ELF 架构检查、只读处理、暂存目录安装。
4. `FfmpegRuntimeLoader` 在后台加载已安装的运行库；没有运行库时保留原有 MediaCodec 播放路径。
5. `FfmpegPlayerRegistry` 为每个 ExoPlayer 单独创建和管理 DecoderManager，避免主播放器与备用播放器共用同一个管理器。
6. 主播放器、无感连播备用播放器和第三方片源播放器均已接入 NextRenderersFactory，并在释放时解绑各自的 DecoderManager。
7. 设置页已增加 FFmpeg 运行库安装入口和视频解码策略选择；支持自动、仅硬件、系统软件、FFmpeg 软件解码。运行库下载后会校验并在后台加载。
8. 应用启动时会恢复用户选择的解码策略，并在后台尝试加载已安装的运行库。
9. FFmpeg 原生构建工作流会编译四种 ABI，生成 AAR、按 ABI 拆分的 ZIP、SHA256SUMS 和通用 ABI 清单模板；软解实验工作流负责编译 APK。

## 运行库清单格式

App 设置页已预填固定清单地址 `https://github.com/xycvip/HongGuoTV/releases/download/ffmpeg-runtime-latest/manifest.json`。安装器自动识别当前应用进程 ABI，再从 `abis` 节点选择对应库列表：

```json
{
  "version": "0.1.0",
  "abis": {
    "arm64-v8a": {
      "libraries": [
        {
          "file": "libavutil.so",
          "url": "https://你的服务器/ffmpeg-runtime/arm64-v8a/libavutil.so",
          "sha256": "64位十六进制SHA256",
          "loadOrder": 1
        }
      ]
    },
    "armeabi-v7a": { "libraries": [] },
    "x86": { "libraries": [] },
    "x86_64": { "libraries": [] }
  }
}
```

每个 ABI 的 `libraries` 必须包含构建工作流生成的五个必需库：`libavutil.so`、`libswresample.so`、`libswscale.so`、`libavcodec.so`、`libmedia3ext.so`。原生构建工作流会把五个库分别作为 Release 资产上传，并生成带真实下载 URL 和 SHA-256 的 `manifest.json`。固定 Release 标签为 `ffmpeg-runtime-latest`，每次成功构建会覆盖该标签下的运行库资产；该 Release 是独立预发布资源，不会改变 APK 正式版/测试版发布通道。

## 当前尚未完成

- 尚未确认四种 ABI 的原生构建工作流全部成功。
- 尚未将真实运行库 ZIP 和通用 ABI 清单发布到稳定下载地址；需要先确定托管位置并替换模板 URL。
- 尚未验证运行库清单与真实原生库包在四种 ABI 上完全匹配。
- 尚未完成 Android 设备实测，不能宣称 H.264 / HEVC 已经成功软解。
- 尚未完成性能、内存、温度、长时间播放和无感连播稳定性验证。

## 安全与回退要求

- 清单及原生库地址只允许 HTTPS。
- 文件名限制为原生库文件名，不允许路径穿越。
- 每个文件都必须通过 SHA-256 和 ELF ABI 校验后才会进入正式目录。
- 新版本全部校验成功后才替换对应运行库，失败时保留旧版本。
- 原生库只存放在应用私有目录，不申请公共存储权限。
- FFmpeg 未安装或加载失败时，播放器必须继续使用原有 MediaCodec 路径。
- 原生库加载失败后不得反复在同一进程切换不同版本；需要提示重启后重试。

## 后续顺序

1. 先完成四种 ABI 原生构建并检查 ZIP 内容、ELF 依赖和 SHA-256。
2. 确定稳定的运行库托管地址，并生成真实 ABI 清单。
3. 接通 App 内下载入口、安装状态和解码模式设置。
4. 完成 APK 编译，再分别测试 32 位、64 位设备及 H.264 / HEVC 视频。
5. 验证主播放器、无感连播和第三方片源播放器的创建、切换、释放流程。
6. 实测失败回退、内存占用、温度和连续播放稳定性后，再考虑合并到正式分支。
