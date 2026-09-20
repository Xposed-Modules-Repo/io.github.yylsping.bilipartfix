# 7040300 live decoder research

> 2026-09-20，推荐直播间“夜莲Annbbers”，OnePlus PLQ110 / Android 16。debug instrumentation 只读，release 不运行。

## 静态链

7040300 live backend 为 `com.bilibili.bililive.playercore.media.ijk.c` 创建的 `tv.danmaku.ijk.media.player.IjkMediaPlayer`。live resource 的 hardware 字段为 true 时设置 `mediacodec=1`，默认 `hw-decode-fallback-enable=1`。独立 listener `com.bilibili.bililive.playercore.media.ijk.b.onMediaCodecSelect()` 接收 MIME/profile/level，并调用 live helper `e.e(mime)`；后者复用共享 `IjkCodecHelper.getBestCodecName`，所以 OMX/C2 rank 与 VOD 相同。

live 也读取 `mediacodec-fake-name-string` 并生成 block regex；`android-variable-codec-black-list` 只控制 variable codec。未发现额外的 live-only codec blacklist。`async-init-mediacodec` 可预置缓存的 AVC/HEVC codec name，listener 仍在实际初始化时运行。

## Low-latency 差异

设备同时提供 `OMX.qcom.video.decoder.hevc.low_latency` alias 与实际 `c2.qti.hevc.decoder.low_latency`。live 工厂没有调用 VOD 的 `IjkCodecHelper.addUnusedLowLatencyDevices(d.M())`：

- 冷启动直进 live：HEVC low-latency alias rank 700，被选择；实际 component 是 `c2.qti.hevc.decoder.low_latency`。
- 同进程先播放 VOD：VOD 把 OnePlus 写入共享 unused-low-latency 集合，low-latency rank 变 600，随后 live 可落到普通 `c2.qti.hevc.decoder`。

这是进程级共享 selector 状态差异，但两者都为 vendor 硬解，尚无证据证明任一更差或需模块纠正。

## 运行时

| 项目 | 结果 |
|---|---|
| Stream | HEVC，1280×720，约 30fps |
| Host preference | hardware=true |
| Java selected name | `OMX.qcom.video.decoder.hevc.low_latency`，profile 1 / level 120，rank 700 |
| Actual decoder | `c2.qti.hevc.decoder.low_latency`，non-secure HW |
| 稳定性 | 启动追帧后 render≈30、discard=0，连续样本稳定 |
| AVC | selector cache 为 `OMX.qcom.video.decoder.avc`，但本轮房间实际未切到 AVC，故不伪造 actual AVC 结论 |
| Software decode | 未观察到；`ff_video_dec` 字样来自 MediaCodec wrapper 日志，资源管理器仍有 HW client，不等价于 FFmpeg software path |
| Dynamic rebuild | 进入/重新进入 room 会创建新 IJK player/codec client；本轮未形成可归因的清晰度/线路切换矩阵 |

未进行 5min 软件 vs 强制硬解功耗对照，因为 host 已稳定选择合适 vendor hardware，缺少实验性 override 的前提；也没有测得可信 live latency 数值。当前证据不支持替换已稳定的 low-latency decoder。

## 建议

`No actionable live decoder conflict found`，最终建议 `USE_HOST`。不接入 VOD 三模式，不新增 `LiveDecoderPolicy`，不改 fnval、清晰度、线路、低延迟或服务端 codec preference。
