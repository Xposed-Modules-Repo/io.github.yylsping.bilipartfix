# 7040300 decoder cloud / blacklist audit

> 配置来自 7040300 smali；当前值来自 OnePlus PLQ110 debug 只读快照。没有规则被绕过。

| Key | 默认值 / 读取点 | 匹配与作用层 | 当前设备 | C2 风险判断 |
|---|---|---|---|---|
| `mediacodec-fake-name-string` | `""`; `BLRemoteConfig.getString`; VOD `d.G()`、live `e.A()` | 逗号/通配符转 regex，命中 codec name 后将候选降到 rank 200；不是全局禁硬解 | 空 | 能按 decoder name 命中 C2，但本机未命中；未知原因时绝不绕过 |
| `ijkplayer.h265-cpu-blacklist` | `""`; `ConfigManager`; `d.t0()` | CPU name 子串；抑制 HEVC 支持 | 未命中，`h265CpuBlocked=false` | 按 CPU，不按 C2 名称 |
| `ijkplayer.disable-weak-h265` | `0`; host config; `d.v0()` | 开启时要求已选 HEVC codec 支持 1920×1080/6Mbps | 未阻断 | 能间接影响 HEVC，但不是 C2 regex |
| `android-variable-codec-black-list` | `""`; `BLRemoteConfig`; `d.x0()` / live `e.N()` | CPU 子串、Huawei P7、API≤22 的 `android6.0`；只禁 variable codec switching | `false` | 不直接禁基础硬解 |
| `ijkplayer.enable-ndk-mediacodec-control-blacklist` | `""`; `ConfigManager`; `d.H()` | model regex；控制 NDK MediaCodec feature | 列表不含 PLQ110，功能值 false | 不直接改变 Java selector rank |
| `ijkplayer.enable-codec-power-control-blacklist` | `""`; `ConfigManager`; `d.I()` | model regex；控制 power mode | 空，功能值 false | 不直接按 C2 命中 |
| `ijkplayer.android_not_use_low_latency_codec` | `""`; host config; `d.M()` | brand/model JSON；调用 `addUnusedLowLatencyDevices`，把匹配设备的 low-latency OMX alias 降到 600 | OnePlus 通配命中 | VOD 有效；直播工厂未调用该注入点，见 live 文档 |
| `hw-decode-fallback-enable` | `1`; live `e.D()` | 开启 live IJK 硬解失败回退 | true | 安全 fallback，绝不绕过 |

## 分层结论

- 本地静态：`IjkMediaCodecInfo` 的 rank/name 规则。
- 云端动态：fake-name、variable-codec、NDK/power/low-latency 配置。
- 设备/ROM：model、brand、CPU、旧 Android 版本匹配。
- codec-name：只有 fake-name/block 直接匹配 decoder name并降 rank。
- stream-profile：本轮没有发现独立的云端 profile blacklist；profile/level 仍参与 selector/native 能力路径。

当前设备没有现代 C2 误伤：fake-name 为空，HEVC CPU 与 variable blacklist 均未命中；VOD 的 OnePlus low-latency 规则有意偏向普通 decoder，但实际仍为稳定硬解。未发现已验证过时且能解释 `host=false` 的规则。Smart Auto 因此不能安全绕过任何 blacklist。
