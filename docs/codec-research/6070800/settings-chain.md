# settings-chain.md — 解码模式设置全链路（UI → 资源 → key → 存储 → 读取 → 决策）

## 1. 实机 UI（Confidence: HIGH，截图 `research/6070800/runtime/codec_mode_page.png`）

入口路径：**我的 → 设置 → 播放设置 → 解码模式选择**

页面形态：独立子页面（Fragment），单选列表，三个选项，实机原始文案：

| 选项 | 主文案（实机） | summary（实机） | 选中效果 |
|---|---|---|---|
| Auto | 自动选择（推荐） | 根据机型和视频智能适配 | 单选圆点，立即生效 |
| V3 | V3硬解优先 (ijkplayer) | 硬解播放，性能好，兼容性强 | 同上 |
| SW | 软解优先（兼容模式） | 冬季暖手必备，偏重兼容性，适用所有手机系统 | 同上 |

- 默认选择：Auto；本机实测当前值曾为 Auto。
- 切换即时生效，无 Toast / Dialog，不需重启 App；新值在下一次播放器 prepare 时被读取（重建播放器后生效）。
- 无设备条件隐藏（本机三选项均可见可点）。
- 点击上报事件：`player.player.decoding-mode.0.click`（type=order+1）。

## 2. 资源层定位（Confidence: HIGH）

| 项 | 值 |
|---|---|
| Preference XML | `r/i/h.xml`（R.xml.h = 0x7f140007） |
| Widget | `tv.danmaku.bili.widget.RadioGroupPreference`（自定义单选 preference，persistString） |
| key | `pref_player_codecMode_key`（R.string 0x7f1122d3） |
| 标题资源 | `pref_title_category_codecMode` = "解码模式选择" |
| entries | 0x7f030036 = [自动选择（推荐）, V3硬解优先 (ijkplayer), 软解优先（兼容模式）] |
| entryValues | 0x7f030037 = **["0", "4", "1"]** |
| summaries | 0x7f030038 |
| radioDefaultValue | "0" |
| Fragment | `com.bilibili.app.preferences.PreferenceTools$CodecModeFragment` |

## 3. 存储（Confidence: HIGH，实机验证）

- 文件：`/data/data/tv.danmaku.bili/shared_prefs/bili_main_settings_preferences.xml`
- 条目：`<string name="pref_player_codecMode_key">"0|4|1"</string>` — **String 类型**
- 实机 UI 切换三模式，文件值依次为 "0" / "4" / "1"（Phase 4 已逐项验证）
- mode 映射：**Auto=0，V3=4，SW=1**

## 4. 读取点（Confidence: HIGH，静态）

- 读取器：`m3.a.c.h.b.c.a(Context)` — 从 xpref 读 `pref_player_codecMode_key` 得 int；读取端用 getInt + ClassCastException 兜底 parseInt（兼容 String 存储）。
- 写入播放器参数：`m3.a.c.h.a.a(Context, PlayerParams)`（"PlayerParamsHelper: Applying params from preferences."）：
  - `VideoViewParams.a = codecMode`
  - `VideoViewParams.c = (huawei p7 || SDK<17) ? 1 : 2`（渲染相关，与 codec 无关）
- 点播（biliplayerv2）：`tv.danmaku.biliplayerv2.service.b1`（~L1729）：`D = prefs.getInt(key,0) != 1` → 经 `m3.a.f.a.e.l.a.c` 映射 `media_stream_extra_enableHwCodec_boolean` → `IjkMediaConfigParams.mEnableHwCodec`。
- 直播：`x1.c.g.b.b.a.b.d()`：`mEnableHwCodec = (mode==0 || mode==4)`。

## 5. 决策点（Confidence: HIGH，静态）

`tv.danmaku.biliplayer.basic.adapter.h.c.d(Context, VideoViewParams)`（implements `tv.danmaku.biliplayer.basic.context.b`）：

```text
codecMode == 1 || codecMode == 2
    → PlayerCodecConfig{ player=IJK_PLAYER, use_ijk_media_codec=false }
否则（0=Auto, 4=V3）
    → PlayerCodecConfig{ player=IJK_PLAYER, use_ijk_media_codec=true }
```

即：**三模式共用 IJK_PLAYER 后端**；SW 只是关掉 `use_ijk_media_codec`；Auto 与 V3 在 Java 层无任何差异。

## 6. 相邻但无关的设置

- HEVC 总开关：`bili_ijk_settings_preferences.xml` 的 `pref_key_is_ijkplayer_enable_h265`（本机=true）→ 影响 `mediacodec-hevc=1` 是否下发，与 codecMode 正交。
