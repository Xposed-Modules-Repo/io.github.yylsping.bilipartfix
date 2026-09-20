# sony-xperia1iii.md — Sony Xperia 1 III 定制版特化排查

## 结论（Confidence: HIGH）

**该定制版在 codec / 播放器层没有任何 Sony 专属逻辑。Sony 差异仅存在于：渠道归因（预装渠道文件）+ 品牌名显示 + 索尼音乐"内容源"标记。**

## 排查方法

- JADX 全库搜索：`Sony` / `sony` / `Xperia` / `Build.MANUFACTURER` / `Build.BRAND` / `Build.MODEL` / `ro.product` / `ro.vendor`，逐个命中类人工核读。
- native：libijkplayer.so / libijksdl.so / libijkffmpeg.so 字符串搜索 sony/xperia。
- 注意：本次实机为 OnePlus PLQ110（Android 16），非 Xperia 1 III；因此运行时 Sony 分支无法被触发验证，结论基于静态证据 + 命中点性质判断。

## 命中清单（全部）

### 1. `tv.danmaku.bili.utils.g0` — 预装渠道识别（渠道归因，与播放无关）

- `g0.b` 构造时取 `Build.MANUFACTURER` 小写；`l()` = `j("sony", "sony")`：渠道字符串含 "sony" 且 manufacturer=="sony" 时命中。
- 命中后返回策略类 `g0.k`，其 `b()` 读取 **`/oem/deletable-app/sony_bili_channel.txt`** 作为渠道号（log tag "preassemble"）。
- 同文件还有 xiaomi/vivo/oppo/huawei/honor/samsung/meizu/lenovo/coolpad/nubia/blackshark 等品牌的对称实现（各读各的 OEM 渠道文件）。
- xref：仅被 `tv.danmaku.bili.utils.a0`（渠道管理）引用；无任何 player/biliplayer/ijk 引用。
- 性质判断：这是"Sony Xperia 1 III 定制版"得名的机制 —— Sony 手机出厂预装时 /oem 分区带渠道文件，App 首启读取上报渠道。APK 本身无 Sony 资源/类差异。

### 2. `com.bilibili.app.preferences.utils.c` — 品牌中文名映射（显示用）

- `@Deprecated`，HashMap 把 `sony`/`sony corporation`/`sony_nw` → "索尼"，`sonyericsson`/`sony ericsson`/`semc` → "索爱"。
- 输入 Build.MANUFACTURER/MODEL/DEVICE，输出用于设备信息展示（如设置-关于）。无功能逻辑。

### 3. `bundle_key_sony_video_source` — 索尼音乐内容源标记（内容侧，非设备侧）

- `tv.danmaku.biliplayer.features.options.PlayerOptionsPlayerAdapter`（refreshBackgroundAudioState / setBackgroundAudioState）与 `PlayerOptionsPanelHolder`（L599）：
  - 当播放的**视频内容**是索尼音乐源（如"索尼音乐中国"UP 的 MV）时，禁止"后台播放（听视频）"并 toast `player_toast_video_source_not_support_background`。
- 与设备是否 Sony 无关；不影响 decoder / codec / MediaCodec。

### 4. 其它 Build 特判（非 Sony，顺带记录）

- `tv.danmaku.biliplayer.utils.b.a()`：Meizu M1852 特判（MANUFACTURER=meizu && MODEL 含 M1852）。
- `m3.a.c.h.a.o()`：Build.MODEL 含 "huawei p7" → `VideoViewParams.c=1`（渲染层参数，非 codec）。
- `com.bilibili.bililive.playercore.android.utils.c.a()`：直播侧 Meizu 特判。
- `IjkCodecHelper`/`IjkMediaCodecInfo`：仅使用 `Build.VERSION.SDK_INT`，无品牌判断。

## 对排查问题的逐条回答

| 问题 | 答案 |
|---|---|
| Sony 定制版改了什么 | 渠道归因读取（/oem/deletable-app/sony_bili_channel.txt）；APK 无 Sony 专属资源/class/native |
| 是否影响 codec selection | 否（HIGH） |
| 是否影响默认 mode | 否（HIGH；radioDefaultValue="0" 无设备条件） |
| 是否影响 MediaCodec | 否（HIGH） |
| 是否针对 Xperia 1 III / Qualcomm 特判 | 无 Sony 特判；Qualcomm 仅出现在通用 selector known-list（OMX.qcom.video.decoder.avc 等，面向所有 OMX 时代高通机）（HIGH） |
| 4K/HDR/高刷/21:9 Sony 逻辑 | 无（HIGH；4K 支持走通用 isUhdSupport(hevc)） |
| Surface/renderer 特殊处理 | 无 Sony 相关；唯一渲染特判是 huawei p7（HIGH） |
| Auto 默认值被 Sony flavor 修改 | 否（HIGH） |
