# 选关页恢复回放

`luxcavation-retained.png` 来自 2026-09-10 12:54 的用户预览截图。
源 SHA-256：`e0886cb82f1a5651d1c159987be5062994724a29ad94cb88cfe5176f716112f5`。
裁出 `[23,149,565,454]`，OpenCV INTER_LINEAR 归一为 1280×720，遮去上方 FPS/宿主状态
与下方账号货币及宿主操作层，仅保留 y=90..599 的游戏画面。

使用未改动的 LALC `luxcavation.png` 在标题区域、阈值 0.85 下确认保留的选关页面。
`RetainedPageNativeTest` 验证恢复动作立即发出上游 Esc 而不报告等待登录，并确认已加载
主页的实际截图不会再进入恢复。发送 Esc 后的游戏转场仍需真机验证。
