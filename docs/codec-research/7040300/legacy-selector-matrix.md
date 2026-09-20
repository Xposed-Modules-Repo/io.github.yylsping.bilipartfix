# 7040300 Host Auto / V3 legacy selector matrix

| Stream | Host Auto | Host reason | V3 actual decoder | Modern capability | Stable | Legacy conflict |
|---|---|---|---|---|---|---|
| AVC 1080P | HW | host preference=true；OMX qcom alias rank 800 | `c2.qti.avc.decoder` | supported | yes | no |
| AVC 1080P60 | HW | same | `c2.qti.avc.decoder` | supported | 60fps / discard 0 | no |
| HEVC 1080P | HW | host preference=true；OMX qcom alias rank 700 | `c2.qti.hevc.decoder` | supported | yes | no |
| AVC 4K-class | HW | host preference=true；alias resolves to vendor C2 | `c2.qti.avc.decoder` | supported | 60fps / discard 0 | no |

原始 `c2.qti.*` 在 Java selector 中确实只有 rank 100，但同一组件的 OMX alias 被选择并映射回 vendor C2。未找到真实的 host software case，因此没有填写推测性的 HIGH conflict，也没有新增生产 override。
