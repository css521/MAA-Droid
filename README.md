<div align="center">

# MAA Droid

**在 Android 上运行游戏自动化任务**

集成明日方舟 MaaCore，并开发基于 LALC 的边狱公司引擎。设备取帧与输入通过 Shizuku 或 Root 提供。

[![License](https://img.shields.io/github/license/css521/MAA-Droid?style=flat-square)](LICENSE)

**[English](README_EN.md)** | **中文**

</div>

---

> 可使用已授权的 Shizuku，无需 Root。边狱引擎仍在开发中。

## 支持的游戏

| 游戏 | 引擎 | 上游 | 状态 |
|---|---|---|---|
| 明日方舟 | `:engine:arknights` | [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | 可用 |
| 边狱公司 Limbus Company | `:engine:limbus` | [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) | 开发中 |

## 资源更新

- **方舟**：沿用 MaaResource 的资源更新链路
- **边狱**：CI 自动跟随上游 LALC tag，打包约 43 MB 的资源包发布到本仓库 Release。App 从 Release 下载，无需下载上游整个仓库（~100 MB）。资源包含任务定义、语言表、模板和 ONNX 模型

资源更新只覆盖当前引擎支持的数据与动作。新增原生逻辑或不兼容协议需要更新 App；边狱会在安装时检查兼容性，失败保留旧资源。

## 特性

|  | 特性 | 说明 |
|---|---|---|
| 🧠 | **原生运行** | 直接在 Android 上运行自动化逻辑，无需 PC 或模拟器 |
| 🎮 | **多游戏** | 在后台任务页切换游戏；各游戏分别保存任务配置 |
| 🪟 | **后台运行** | 通过虚拟显示器在后台执行，不影响手机正常使用 |
| 📦 | **方舟任务** | 理智作战、公招识别、基建托管、抄作业、自动肉鸽等 |
| 🎲 | **边狱任务** | 经验/纺锤副本、镜牢自动化、邮件领取 |
| ⏱️ | **定时任务** | 按预设时间自动启动任务 |
| 🔄 | **自动更新** | 应用与资源独立更新，资源热更不需要重装 App |

## 运行要求

| 项目 | 要求 |
|---|---|
| 系统版本 | Android 9+（API 28） |
| 权限方案 | [Shizuku](https://shizuku.rikka.app/) 已运行并授权，或设备已 Root |
| 设备架构 | arm64-v8a 或 x86_64 |

## 构建

```bash
# 环境要求：JDK 17+, Android SDK (compileSdk 37)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/path/to/android-sdk

# 下载 MAA Core 和 OCR 模型
python3 scripts/setup_maa_core.py

# 构建 debug APK
./gradlew :app:assembleDebug

# 构建 release APK（需要签名配置）
./gradlew :app:assembleRelease
```

详见 [构建指南](docs/zh-cn/develop/BUILDING.md)。

## 与上游项目的关系

- **本项目不是 MaaAssistantArknights 的官方 Android 端**，通过 JNA 加载上游发布的 `libMaaCore.so` 驱动明日方舟
- **边狱引擎移植自 LixAssistantLimbusCompany**，是其 AGPL-3.0 衍生作品。资源包的流水线定义、模板素材与 ONNX 模型取自上游；Python 动作由 Kotlin 重写
- 两个上游项目均以 AGPL-3.0 发布，本项目沿用同一许可证

## 致谢

- [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) — 明日方舟游戏小助手
- [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) — 边狱公司助手，本项目边狱引擎的移植来源
- [AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany) — 边狱公司助手，安卓适配参考
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 免 Root 权限方案

## 许可证

[AGPL-3.0](LICENSE)
