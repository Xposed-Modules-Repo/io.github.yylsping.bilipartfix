# native-arm64.md — arm64-v8a native 播放链路

## 1. native 库清单（Confidence: HIGH，从设备 base.apk 提取，`research/6070800/native/`）

| 文件 | 大小 | SHA-256 | 说明 |
|---|---:|---|---|
| libijkplayer.so | 759,848 | `b527f69caa8b9a2740ab2a087e30a39ef86dca0d94fac35cb11ccb67d066b884` | ijkplayer + mediacodec 桥，stripped，BuildID 9ce6a428… |
| libijkffmpeg.so | 5,666,224 | `8f23f82afa88b5438567c957cedebb55bf0efd095936c362df5d6b827334f391` | FFmpeg（demux/解码/swscale） |
| libijksdl.so | 546,968 | `422de9f23f44b97ead6b80b1e84d73bf8efd728cf5900305d58ec497a1212c01` | SDL 渲染/音频输出 |

- ELF: 64-bit aarch64；stripped（用字符串 xref / JNI 注册表定位）。
- APK 仅含 arm64-v8a，无 32-bit 库。

## 2. MediaCodec option 字符串（libijkplayer.so，静态 HIGH）

```text
mediacodec
mediacodec-avc
mediacodec-hevc
mediacodec-mpeg2 / mediacodec-mpeg4
mediacodec-auto-rotate
mediacodec-handle-resolution-change
mediacodec-sync
mediacodec-default-name / mediacodec-default-avc-name / mediacodec-default-hevc-name
```

与 Java 组装层（IjkMediaPlayerItem）一一对应 —— 7.4.0 搜索锚点。

## 3. JNI / codec select 回调（静态 HIGH）

- JNI 注册：`J4AC_..._IjkMediaPlayerClient__onSelectCodec` → Java `tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient.onSelectCodec` → AIDL 回主进程。
- VOD 未覆写 → 返回 null（native 自选默认 codec）；直播注册了 selector listener。
- 相关 native 字符串：`"amc: use default avc codec"`、`"amc: no suitable codec"`。

## 4. fallback（静态 HIGH + 实机 HIGH）

native 字符串（libijkplayer.so）：

```text
MediaCodec:AMEDIACODEC__UNKNOWN_ERROR error will try fallback to ffplay decoder
quirk: reconfigure with new codec
reconfigure_codec
codec change ... restart decoder
```

实机命中（`research/6070800/runtime/logcat_auto_live.txt` L1255-1283）：

```text
QC2Buf: allocate: failed to alloc graphic block w(512)/h(288)… Error: 14
QC2V4l2Decoder: [v4lhvcDL_381] Error during buffer allocation (err = e)
MediaCodec: Codec reported err 0x80000000/UNKNOWN_ERROR, actionCode 0, while in state 6/STARTED
IJKMEDIA: [Thread-19] SDL_AMediaCodecJava_stop: stop
IJKMEDIA: [Thread-19] func_run_sync MediaCodec:AMEDIACODEC__UNKNOWN_ERROR error will try fallback to ffplay decoder
```

即 native 层内直接退 FFmpeg 软解（不重建播放器）。Java 层另有 `SwitchablePlayerAdapter.switchPlayer` 二级兜底（重试至 2 次后按策略 (IJK,mc=true)→(IJK,mc=false) 重建播放器，并发 "BasePlayerEventCodecConfigChanged" 事件）。

## 5. profile gating（静态 HIGH）

libijkplayer.so 内 H264 profile 开关：
- 启用：BASELINE / MAIN / EXTENDED / HIGH
- 禁用：HIGH_10 / HIGH_422 / HIGH_444 / CAVLC444（10-bit/422/444 走不了硬解）

## 6. Sony / Qualcomm 特殊逻辑（静态 HIGH：无）

- libijkplayer.so 字符串中无 Sony / Xperia 相关内容。
- codec 名相关字符串为通用 OMX./c2. 机制，无 Qualcomm 专属硬编码路径（qcom 仅出现在通用 selector 日志与 Java known-list）。
- 无 21:9 / HDR / 高刷的 Sony 专属 native 分支。

## 7. 关键定位方法（供 7.4.0 复用）

1. `strings` 搜 `mediacodec` → option table xref → `ffpipeline` 创建条件。
2. JNI 注册表（J4AC_onSelectCodec）→ Java 回调 → selector。
3. fallback 字符串 `will try fallback to ffplay decoder` 是判断"该版本 native ijk 是否仍带自动软解兜底"的最快锚点。
4. 未做函数级 RVA 标注（stripped；如需精确 offset 可用 IDA 载入 libijkplayer.so，本次未需要）。
