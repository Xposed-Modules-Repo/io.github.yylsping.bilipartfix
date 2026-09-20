# migration-to-7040300.md — 7040300（哔哩哔哩 7.4.0）V3 恢复迁移报告

> 交接对象：执行 `bili-part-fix-v3-codec-goal-prompt.md` 的 coding agent。
> 本文件 + `machine-readable-summary.json` 应足以直接开始 7040300 的 DEX/native 搜索与 runtime instrumentation，无需重新研究 6070800。
> 6070800 的混淆类名（m3.a.c.h.b.c 等）**不要直接复制到 7.4 Hook**；用下方的真名锚点/字符串锚点/结构锚点重新定位。

## 6070800 中已确认事实（可直接作为对照基线）

1. 三模式共用 IJK_PLAYER 后端；SW=不下发 mediacodec 键；V3=`mediacodec=1`(+HEVC 时 `mediacodec-hevc=1`)；Auto 与 V3 在 Java 层等价。（HIGH）
2. codec mode 存储：key `pref_player_codecMode_key`，String，值 0/4/1，默认 0，文件 `bili_main_settings_preferences.xml`。（HIGH）
3. 播放器跑在独立 `:ijkservice` 进程，AIDL 代理 `IjkMediaPlayerItem` → `IjkMediaPlayerService`/`IjkMediaPlayerItemClient`（native _setOption）。（HIGH）
4. option 组装在 prepareAsync 之前，按 category 分 bundle 下发；codec 相关在 category 3。（HIGH）
5. Java selector（IjkCodecHelper）以 omx. 前缀为中心，c2-only 设备上必然选空 → native 用系统默认 codec；实际 decoder 由平台决定（实机：c2.qti.hevc.decoder.low_latency）。（HIGH）
6. MediaCodec 失败时 native 自动退 ffplay（字符串 "will try fallback to ffplay decoder"），Java 另有 SwitchablePlayerAdapter 重试≤2 次后重建为软解。（HIGH）
7. H264 10-bit/422/444 profile 不走硬解（native gating）。（HIGH）
8. 6070800 无任何 Sony codec 特化。（HIGH）

## 6070800 中不能直接迁移的版本特定细节

- 全部混淆类名（m3.a.c.h.*、h.c.d、b1、x1.c.g.* 等）与 R id。
- IjkMediaPlayerItem 的 Bundle 组装代码行号与具体 category 编号约定（7.4 可能重排）。
- 云控 key 名（mediacodec-fake-name-string 等）需在 7.4 重新确认是否沿用。
- 老 selector rank 表（omx. 中心）在 7.4 可能已重写 —— 7.4 应检查其是否已支持 c2.*。
- 6070800 的测试机结论（c2.qti.hevc.decoder.low_latency）只代表该机该流，不是通用值。

## 7040300 第一轮应搜索的真名类（P0）

```text
tv.danmaku.ijk.media.player.IjkMediaPlayer
tv.danmaku.ijk.media.player.IjkMediaCodecInfo
tv.danmaku.ijk.media.player.IjkCodecHelper        （或改名，按结构锚点找）
tv.danmaku.ijk.media.player.services.IjkMediaPlayerService
tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient
tv.danmaku.ijk.media.player.IMediaPlayer
```

## 7040300 第一轮应搜索的字符串（P0）

```text
mediacodec            mediacodec-hevc        mediacodec-avc
mediacodec-default-name    mediacodec-default-avc-name    mediacodec-default-hevc-name
pref_player_codecMode_key  （设置项 key 可能沿用）
codecMode / CodecMode
use_ijk_media_codec / enableHwCodec
V3硬解 / 软解优先 / 自动选择   （若 UI 资源仍含）
player.player.decoding-mode  （埋点事件名）
```

## 7040300 第一轮应搜索的 native strings（P0，libijkplayer.so 或合并后的播放 so）

```text
mediacodec / mediacodec-hevc / mediacodec-avc
will try fallback to ffplay decoder     ← 判断 native 自动软解兜底是否仍在的最快锚点
amc: use default
amc: no suitable codec
onSelectCodec / J4AC_.*onSelectCodec
mediacodec-default-name
```

若 7.4 的 native 仍有这些字符串，播放器 lineage 与 6070800 相同，迁移成立；若 `will try fallback to ffplay decoder` 消失，说明 fallback 结构变化，需要重新分析。

## 7040300 应寻找的结构模式（P1）

1. **决策点结构**：一个方法/类，输入 int codecMode（或 settings 读取值），输出包含 {player backend 枚举, boolean useMediaCodec} 的配置对象；SW 分支使 boolean=false，其余 =true。6070800 中它是 `h.c.d()`。
2. **option 组装结构**：prepare 前遍历 Map/Bundle 调 `setOption(category, key, value)`；codec 组集中在同一 category；含 `mediacodec` 键的条件分支。
3. **AIDL 跨进程**：主进程代理类 + `:ijkservice`（或类似命名）进程内 real 实现；setOptionBundle 批量转发。
4. **fallback 结构**：onError/onInfo 监听 → 计数重试 → 按策略表（先硬后软）重建 player；事件名类似 "BasePlayerEventCodecConfigChanged"。
5. **selector 结构**：枚举 MediaCodecList + rank/打分 + blacklist 正则；输出 codec name 或 null。

## 推荐的 runtime instrumentation（P0/P1）

只读优先，hook 点从低到高：

1. logcat 过滤 `IJKMEDIA / CCodec / CCodecConfig / MediaCodec / IjkMediaCodecInfo` + `dumpsys media.resource_manager`（直读 actual decoder Name，最省力且不可伪造）。（P0）
2. `/proc/<ijkservice pid>/task/*/comm` 线程清单区分 SW（ff_video_dec 且无 MediaCodec_loop）与 HW。（P0）
3. Frida hook `IjkMediaPlayer` 的 setOption 系列（打印 category/key/value）验证 option 下发。（P1）
4. Frida hook 决策点结构方法（打印 codecMode 输入与 boolean 输出）。（P1）
5. 需要崩溃吞异常时注意：测试机隐藏模块会使播放 onInfo 链 FATAL，可对等 hook onInfo try/catch（仅测试环境需要）。（P2）

## 最可能的 Hook 点候选（按优先级）

| 优先级 | Hook 点 | 理由 |
|---|---|---|
| P0 | **决策点**（codecMode → {backend, useMediaCodec} 的方法） | 单点控制三模式；7.4 若砍掉 UI，直接在此处强制 V3 语义最干净 |
| P0 | **option 组装点**（mediacodec 键下发处） | 字符串锚点稳定，易定位；可直接补 `mediacodec=1`/`mediacodec-hevc=1` |
| P1 | IjkMediaPlayer.setOption wrapper | 兜底层，所有路径必经 |
| P1 | codec selector callback（onSelectCodec） | 需要指定 codec 名时使用；c2 设备上 selector 输出为空是预期 |
| P2 | SwitchablePlayerAdapter 等价物（fallback 策略表） | 需要定制 fallback 行为时 |

## 最短 H.264 1080P V3 PoC 路径

```text
1. 7.4 DEX 搜 "mediacodec-hevc"（字符串）→ 定位 option 组装类
2. 顺 xref 向上找 {codecMode → boolean} 决策点
3. Hook 决策点：任意 mode → useMediaCodec=true（等价 V3）
4. 播 H.264 1080P 视频，logcat+dumpsys media.resource_manager 验证 actual decoder = c2.qti.avc.decoder（或机型对应 HW AVC）
5. 确认 native fallback 字符串仍在（软解兜底可用）
```

判据：`mediacodec=1` 不算完成；必须有 resource_manager/线程/CCodec 层面的 actual decoder 证据。

## 预期 fallback 位置

- 第一级：native ijkplayer 内（mediacodec open/decode 失败 → ffplay decoder），字符串锚点如上。
- 第二级：Java 播放器适配层（重建播放器，策略表先硬后软）。
- 7.4 验证方法：无需破坏系统；可在测试机制造 codec 资源占用（并发占满 HW 实例）或直接静态确认字符串存在。

## Auto 后续应该重点采集的数据

- 7.4 的 Auto 是否已变成真决策（读分辨率/码率/HDR/thermal）还是仍 = V3 + 兜底。
- 云控 codec blacklist key 名与默认值。
- 7.4 selector 是否支持 c2.*（决定是否可主动指定 codec）。
- 不同芯片（QTI/MTK/Tensor）上 7.4 Auto 的 actual decoder 矩阵。

## 环境备忘（7.4 工作时复用）

- 设备：OnePlus PLQ110 / Android 16；隐藏注入模块会让旧版 bili 播放 onInfo 链 FATAL（ClassCastException），需要时 Frida 吞 `b1$i.onInfo` 等价物。
- 新视频对旧版本客户端返回"版本过低"；测试用老视频（如 av170001）。
- 6070800 无反 Frida；7.4.0 attach 会被 libmsaoaidsec.so 终止，需 spawn+gating+bootstrap（kahlo 模块 bilibili-msa-compat@1.0.0）。
