# 7040300 arm64 native 解码能力

## 静态结果

7040300 的 arm64 native 库已提取到 `research/7040300/native/`。`libijkplayer.so` 独立命中以下稳定锚点（STRONG）：

- `mediacodec`、`mediacodec-avc`、`mediacodec-hevc`
- `mediacodec-auto-rotate`、`mediacodec-handle-resolution-change`
- `mediacodec-default-name`、`mediacodec-default-avc-name`、`mediacodec-default-hevc-name`
- `amc: use default`
- `will try fallback to ffplay decoder`
- `enable_ndk_mediacodec`、`enable_ndk_mediacodec_async`
- `AMediaCodec`（58 次字符串命中）与 ffpipenode 相关锚点

Manifest 同时保留 `IjkMediaPlayerService` 和独立 `:ijkservice` 进程。结论是 native V3 path 完整保留，并在 6070800 Java MediaCodec 路径基础上增加了可控的 NDK MediaCodec 路径。

## actual decoder

以下均来自 `dumpsys media.resource_manager` 的 client Name，而非以 option 值推断（DIRECT）：

| 流 | actual client | 配置/反馈 |
|---|---|---|
| HEVC 1080P | `c2.qti.hevc.decoder` | 1920×1080，约 24/25fps |
| AVC 1080P60 | `c2.qti.avc.decoder` | 1920×1080（coded 1920×1088），60fps |
| AVC 4K-class 60 | `c2.qti.avc.decoder` | 3456×2160，59–61fps output、60fps render、0 discard |
| 多 P 4K60 | `c2.qti.avc.decoder` | 4096×1716 |

硬解时 `:ijkservice` 有 `MediaCodec_loop`、`amediacodec_inp` 和单个 `ff_video_dec` 协调线程；软解时没有 video codec client/MediaCodec 线程，而是 `ff_video_dec`×10。

## native fallback

在 H.264 1080P V3 测试中，运行时把请求的 `OMX.qcom.video.decoder.avc` 替换为不存在的名字，MediaCodec 初始化失败后：

- `:ijkservice` 未崩溃；
- resource manager 中没有 video codec client；
- 出现 `ff_video_dec`×10；
- 播放工作负载继续，5 秒 CPU 约 291 ticks。

这直接证明 7040300 当前 native 路径仍能从硬解初始化失败退到 FFmpeg（DIRECT）。没有修改或屏蔽该错误恢复路径。

## 限制

本轮没有使用 IDA，因此不声明具体 native 函数边界或控制流地址；native 静态结论限定为库/字符串/JNI lineage，fallback 结论由故障注入补强。功耗接口 `current_now` 在此设备始终为 0，无法计算平均电流。
