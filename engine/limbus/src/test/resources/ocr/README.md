# EXP 关卡识别回放

来源：用户 2026-09-10 提供的 1278×588 全屏游戏截图。
原图 SHA-256：`367ecbe131cb01e538db37345713ea964cdf83203ea0a28dde51c07588b5cfb0`。
测试仅保留关卡标题区域，账号、货币、桌面与宿主界面均未入库。

宿主 `EngineFullscreenPreview` 居中显示 16:9 画面。按高度换算视口宽度
`round(588 * 16 / 9) = 1045`，裁出后归一为 1280×720，再保留上游
EXP 掩码 `[250, 180, 1000, 50]`，区域外填黑。

- `exp-stage-linear.png`：左边界 116，OpenCV `INTER_LINEAR`。直接截图回放。
- `exp-stage-nearest.png`：左边界 115，`INTER_NEAREST`。一像素偏移和不同采样的回归变体；
  修复前同一 ONNX 模型把 09 读成低分 9、邻近噪声压低 07 的合并分数。
  该变体证明识别对采样的敏感性，不代表已拿到手机当时输入 OCR 的原始帧。

使用 LALC v5.0.0 的原始 PP-OCRv5 det/rec 模型运行 `ExpStageNativeTest`。
测试覆盖真实模型→同帧识别→关卡点击，不能代替 Android 真机后续战斗验证。
