# HongGuoTV libmpv runtime

This module embeds the **libmpv native playback engine**. It does not embed the mpv-android application or its UI.

## Architecture

- `libmpv.so`: mpv playback engine built from the mpv-android native build scripts.
- `libplayer.so`: JNI bridge built from mpv-android's Android JNI sources. The bridge's JNI class name is intentionally kept as `is.xyz.mpv.MPVLib`; this is an internal JNI compatibility layer, not the playback engine.
- `LibMpvPlaybackView`: HongGuoTV-owned SurfaceView and playback API.
- `LibMpvHttpBridge`: loopback-only HTTP Range adapter. HongGuoTV's current `RemoteVideo` stream is encrypted and decrypted on demand; libmpv cannot consume the source URL directly. The bridge exposes those already-decrypted bytes locally and does not issue another upstream request.

## Build

The workflow `libmpv原生运行库构建.yml` checks out mpv-android at `255901dd586fa750000fa0075f69d6ffdd26cd9c` and builds the upstream native dependencies for `armeabi-v7a` and `arm64-v8a`, extracts the native libraries from the upstream universal debug APK, then builds HongGuoTV with this module.

Native libraries are generated during CI and are not committed as opaque binaries.

## Current scope

The engine is isolated from Media3/ExoPlayer and the existing FFmpeg renderer. It supports one active libmpv session because the upstream JNI implementation owns a process-global `mpv_handle`. Do not use it for seamless A/B preloading. Integrate it as an explicit playback-engine selection only after device validation.

## Third-party source

The JNI wrapper API is adapted from [mpv-android](https://github.com/mpv-android/mpv-android/tree/255901dd586fa750000fa0075f69d6ffdd26cd9c) (GPL-3.0). The upstream application UI is not included.
