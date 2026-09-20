# player-codec-chain.md — Settings → Player → IjkMediaPlayer → JNI → native → MediaCodec 完整调用链

每一级标注证据类型（静态/实机）与置信度。

## 链路总图

```text
CodecModeFragment (UI)
  ↓ persistString
bili_main_settings_preferences.xml [pref_player_codecMode_key = "0"/"4"/"1"]
  ↓ m3.a.c.h.b.c.a(ctx) 读 int（容错 String parse）
VideoViewParams.a  (m3.a.c.h.a.a, "PlayerParamsHelper")
  ↓
tv.danmaku.biliplayer.basic.adapter.h.c.d()  决策 → PlayerCodecConfig{IJK_PLAYER, use_ijk_media_codec}
  ↓                                                              (0/4→true, 1/2→false)
b1 (tv.danmaku.biliplayerv2.service): D = getInt(key,0)!=1
  ↓ media_stream_extra_enableHwCodec_boolean → IjkMediaConfigParams.mEnableHwCodec
m3.a.f.a.e.j.a (VOD media item) 创建 IjkMediaPlayerItem + IjkMediaConfigParams
  ↓ AIDL 跨进程
IjkMediaPlayerService (:ijkservice 进程) → IjkMediaPlayerItemClient（native）
  ↓ setOptionBundle(category, Bundle) → _setOption(native)
libijkplayer.so → ijkplayer option table → ffpipeline mediacodec
  ↓ JNI (SDL_AMediaCodec*)
android.media.MediaCodec → CCodec → c2.qti.hevc.decoder（本机实机）
```

## 1. Player factory / backend 选择（静态，HIGH）

- 决策类：`tv.danmaku.biliplayer.basic.adapter.h.c.d()`（implements `tv.danmaku.biliplayer.basic.context.b`）。
- 三模式后端均为 **IJK_PLAYER**；不存在 AndroidMediaPlayer/VLC/其它 legacy backend 参与该决策。
- 播放器创建走 `tv.danmaku.biliplayer.basic.k.g2(l.a)`；`l.a` 接口实现类名未单独拉取（LOW，待补）。

## 2. VOD option 组装（静态，HIGH；源文件 `research/6070800/jadx/IjkMediaPlayerItem.java` ~L1380-1571）

`IjkMediaPlayerItem` 按 category 组装 Bundle，经 `IjkMediaPlayerItemClient.setOptionBundle` 下发（时机：prepareAsync 之前）：

| category | 关键 option | 条件 |
|---|---|---|
| 3 (player) | `mediacodec=1` | mEnableHwCodec（即 mode≠1；SW 模式不下发该键） |
| 3 | `mediacodec-hevc=1` | + mEnableH265Codec（HEVC 开关） |
| 3 | `async-init-decoder=1`, `video-mime-type`, `mediacodec-default-name` | getCodecName(mime) 非空且 HW |
| 3 | `mediacodec-default-avc-name` | getCodecName("video/avc") 非空 |
| 3 | `mediacodec-default-hevc-name` | H265 流 |
| 3 | opensles / enable-accurate-seek / framedrop=1 / buffering-water-mark-string=500,1000,2000,4000,5000 / variable-seek-buffer / enable-variable-buffer / loop / render-after-prepare 等 | 常规 |
| 1 (format) | user_agent "Bilibili Freedoooooom/MarkII", protocol_whitelist(...ijkhttphook...), auto_convert=0, safe=0, cache 路径, enable_ipv6 | 常规 |
| 2 (codec) | skip_frame, skip_loop_filter | 按云控/性能档 |
| 4 | max-cache-time, use-new-find-stream-info, start-position, dns-cache-clear-level, disable-inject-at-start | 常规 |

- `getCodecName(mime)`：SharedPreferences 缓存 + `mCodecFakeNameString` 云控 block 正则 + `IjkCodecHelper.getBestCodecName(mime[,block])`。
- 在 Android 10+ c2-only 设备（含本机）上 getBestCodecName 返回 null（见第 4 节）→ `mediacodec-default-name` 不下发。

## 3. 跨进程模型（静态+实机，HIGH）

- `IjkMediaPlayerItem` 是 AIDL 代理；真正播放器在 `:ijkservice` 进程（`tv.danmaku.ijk.media.player.services.IjkMediaPlayerService` → `IjkMediaPlayerItemClient`，`_setOption`/`_setDataSource` 等为 native 方法）。
- 实机：播放中 `pidof tv.danmaku.bili:ijkservice` 存在；MediaCodec/CCodec/IJKMEDIA 日志全部来自 ijkservice pid；dumpsys media.resource_manager 中 codec client 属主 = ijkservice pid。

## 4. Java codec selector（静态+实机，HIGH；`research/6070800/jadx/IjkCodecHelper.java` / `IjkMediaCodecInfo.java`）

- `MediaCodecList` 全枚举 + rank 制，rank<600 拒绝：
  - 非 `omx.` 前缀 → **100**（⇒ `c2.*` 全部落选）
  - `omx.google`/`omx.pv`/含"ffmpeg" → 200；`.secure` → 300；ittiam → 0
  - `omx.mtk` → 800（API≥18）；命中云控 block 正则 → 200；known-list（如 `OMX.qcom.video.decoder.avc`）→ 800；其它 → 700
- **结论：在 Android 10+ 纯 c2 命名设备上（c2.qti.* / c2.android.*），Java 侧选不出任何 codec，`mediacodec-default-name` 不下发，native 走默认 codec（实机日志/行为一致）。**
- 云控项（IjkOptionsHelper 等）：`mediacodec-fake-name-string`（block 正则源）、`ijkplayer.h265-cpu-blacklist`、`android-variable-codec(-black-list)`（buvid Adler32 %1000 灰度）、`ijkplayer.disable-weak-h265`。
- 能力检测：`IjkCodecHelper.isUhdSupport("video/hevc")`（3840x2160@6Mbps）→ 4K 支持缓存（m3.a.c.h.a.b → f.a）。
- profile/level dump 实机可见（logcat `IjkMediaCodecInfo: Unknown Profile Level ...`），selector 在主进程运行。

## 5. native codec select 回调（静态，MEDIUM）

- JNI 回调 `J4AC_..._IjkMediaPlayerClient__onSelectCodec` → Java `IjkMediaPlayerClient.onSelectCodec` → AIDL `onMediaCodecSelect`：
  - VOD 端未覆写 → 返回 null（native 自选）
  - 直播端注册 listener：`x1.c.g.m.c.h.b.onMediaCodecSelect` → `x1.c.g.m.c.h.e.e(mime)` → IjkCodecHelper

## 6. 直播链路（静态，MEDIUM）

- 直播读同一 key：`x1.c.g.b.b.a.b.d()` 中 `mEnableHwCodec = (mode==0 || mode==4)` — 与 VOD 语义一致（SW=1 关硬解）。
- UGC/PGC 共用 biliplayer 决策（`h.c.d()`）；直播走 bililive playercore 自己的 config 组装，但 mode 语义相同。本地/离线播放（"downloaded" from）未单独验证（LOW）。

## 7. 生命周期（实机，见 runtime-matrix.md）

- seek：decoder 只 flush，不重建（HIGH）。
- 切 P / 自动连播：decoder 重建（旧 MediaCodec teardown → 新 CCodec 配置），player 实例复用（无新 prepare 日志）（MEDIUM-HIGH）。
- 后台→深链重进视频页：完整 prepare + decoder 重建，codec mode 重新读取（MEDIUM-HIGH）。
- 切清晰度：未直接观测成功（竖屏控制条无清晰度入口）；UNKNOWN。
