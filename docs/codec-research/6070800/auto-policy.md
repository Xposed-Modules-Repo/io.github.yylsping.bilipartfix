# auto-policy.md — 6070800 官方 Auto（"自动选择（推荐）"）还原

结论标注规范：FACT（代码/运行时直接证实）/ INFERENCE（合理推断）/ UNKNOWN。

## 一句话结论

**6070800 的 Auto 在 Java 决策层与 V3 完全等价**（FACT）。"自动"不体现在选择硬解/软解上，而体现在三层兜底与云控上：

```text
Auto(0) ──Java 决策──→ 与 V3(4) 相同：IJK_PLAYER + use_ijk_media_codec=true（mediacodec=1）
                              ↓ MediaCodec 失败
                    native ijk 自动退 ffplay 软解（"will try fallback to ffplay decoder"）（FACT，实机命中）
                              ↓ 播放仍失败
                    Java SwitchablePlayerAdapter 重试（≤2 次）后按 (IJK,mc=true)→(IJK,mc=false) 重建（FACT，静态）
                              ↓
                    云控 blacklist 在 selector 层预先规避已知问题 codec（FACT，静态）
```

## 输入条件

| 输入 | 是否被 Auto 使用 | 依据 |
|---|---|---|
| codec mode 存储值 | FACT：是（唯一 Java 输入） | h.c.d() 只读 mode |
| codec MIME / H.264 / HEVC | FACT：间接（mediacodec-hevc 由 H265 开关+流类型决定，与 mode 无关） | IjkMediaPlayerItem |
| 设备 codec 能力（MediaCodecList） | FACT：selector 枚举；但 c2-only 设备选不出（rank 规则） | IjkCodecHelper |
| Android API level | FACT：selector/4K 检测用 SDK_INT | IjkCodecHelper、m3.a.c.h.a |
| manufacturer/model | FACT：无 codec 用途；仅 huawei p7（渲染）、meizu M1852（工具） | 全库搜索 |
| 云控 config | FACT：mediacodec-fake-name-string、h265-cpu-blacklist、android-variable-codec(-black-list)（buvid 灰度）、disable-weak-h265 | IjkOptionsHelper |
| 宽高/fps/bitrate/profile/HDR/thermal/省电 | UNKNOWN：未见 Auto 读取 | — |
| 历史 crash blacklist | UNKNOWN：未见本地持久化 codec crash 记录 | — |

## 决策顺序（FACT）

1. 读 `pref_player_codecMode_key`（默认 0）。
2. mode==1||2 → SW（IJK + 无 mediacodec 键）；否则 → HW（mediacodec=1）。
3. （Auto 到此为止，与 V3 相同。）

## 能力检测

- `IjkCodecHelper.getBestCodecName(mime)`：枚举+rank（非 omx. 前缀=100 拒绝；known OMX.qcom 等=800）。
- `IjkCodecHelper.isUhdSupport("video/hevc")`：HEVC 3840x2160@6Mbps → 4K 可用性缓存（FACT）。
- native profile gating：H264 HIGH_10/422/444/CAVLC444 禁用硬解（FACT，native 字符串）。

## blacklist / whitelist

- 云控 fake-name 正则（block 命中 → rank 200 拒绝）（FACT）。
- h265 cpu blacklist（FACT，静态；触发条件未实机验证）。
- 无本地 Sony/Xperia 名单（FACT）。

## Sony 影响

无（FACT：DEX 全库搜索，见 sony-xperia1iii.md）。

## 可供 7.4 参考的部分

- "Auto=V3 + 多级兜底"结构简单可靠：V3 恢复后无需重做 Auto 决策，给 mediacodec=1 + 保证 native fallback 可用即可。
- 云控 blacklist 机制（fake-name 正则）值得保留对接。
- 二级兜底（重建播放器退软解）是用户无感知的最后一道。

## 不应直接照搬的部分

- Java selector 的 rank 表以 `omx.` 前缀为中心，在 Android 10+ c2-only 设备上完全失效 —— 7.4 若要主动指定 codec，必须自行适配 c2.* 命名（或干脆不下发 default-name，让系统选）。
- HEVC UHD 检测阈值（3840x2160@6Mbps）是 2021 年标准，勿照抄为新机标准。
