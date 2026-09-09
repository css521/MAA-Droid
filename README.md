<div align="center">
<img alt="LOGO" src="/docs/zh-cn/develop/Logo.png" width="256" height="256" />

# MAA Droid

**在 Android 上运行游戏自动化任务**

集成明日方舟 MaaCore，并开发基于 LALC 的边狱公司引擎。设备取帧与输入通过 Shizuku 或 Root 提供。

[![GitHub Release](https://img.shields.io/github/v/release/Aliothmoon/MAA-Meow?style=flat-square&label=Latest)](https://github.com/Aliothmoon/MAA-Meow/releases/latest)
[![License](https://img.shields.io/github/license/Aliothmoon/MAA-Meow?style=flat-square)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/Aliothmoon/MAA-Meow?style=flat-square)](https://github.com/Aliothmoon/MAA-Meow)
[![GitHub Downloads](https://img.shields.io/github/downloads/Aliothmoon/MAA-Meow/total?style=flat-square&label=Downloads)](https://github.com/Aliothmoon/MAA-Meow/releases)

[下载最新版](https://github.com/Aliothmoon/MAA-Meow/releases/latest) · [常见问题](https://docs.maameow.com/faq/getting-started/) · [问题反馈](https://github.com/Aliothmoon/MAA-Meow/issues) · [QQ 交流群](https://join.maameow.com/)

**[English](README_EN.md)** | **中文**

</div>

---

> 可使用已授权的 Shizuku，无需 Root。边狱引擎仍在开发中；通用引擎目前使用后台虚拟显示器，前台模式仍走方舟原有入口。

## 支持的游戏

| 游戏 | 引擎 | 上游 | 状态 |
|---|---|---|---|
| 明日方舟 | `:engine:arknights` | [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | 可用 |
| 边狱公司 Limbus Company | `:engine:limbus` | [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) | 开发中 |

方舟沿用 MaaResource 的资源更新链路。边狱从 LALC 固定提交的源码 ZIP 下载任务定义、语言表、模板和模型；UI 图鉴也从这份已安装资源生成，不再内置批量 PNG 或 catalog。缺资源时先下载，已有队伍、权重和偏好配置会保留。

资源更新只覆盖当前引擎支持的数据与动作。新增原生逻辑、动作类型或不兼容协议仍需要更新 App；边狱会在安装时检查兼容性，失败保留旧资源。

新游戏通过 `GameProfile`、`AutomationEngine`、`EngineUi` 和资源包契约接入，还需要模块依赖、宿主注册及生命周期验证。方舟的核心桥接与 `MaaCoreSession` 已迁入引擎模块，宿主仍承担业务编排和面板；完整通用 provider 尚未接通。详见[架构说明](docs/zh-cn/develop/ARCHITECTURE.md)和[新增游戏指南](docs/zh-cn/develop/ADDING_A_GAME.md)。

<p align="center">
  <img src="docs/zh-cn/manual/screenshots/home.png" width="200" />
  <img src="docs/zh-cn/manual/screenshots/background_task.png" width="200" />
  <img src="docs/zh-cn/manual/screenshots/schedule.png" width="200" />
  <img src="docs/zh-cn/manual/screenshots/auto_controls.png" width="200" />
</p>

## 特性

|  | 特性 | 说明 |
|---|---|---|
| 🧠 | **原生运行** | 直接在 Android 上运行自动化逻辑，无需 PC 或模拟器 |
| 🎮 | **多游戏** | 在后台任务页切换游戏；各游戏分别保存任务配置 |
| 🪟 | **双模式运行** | 方舟支持前台悬浮面板与后台虚拟显示器；通用引擎当前接入后台模式 |
| 📦 | **方舟任务** | 理智作战、公招识别、基建托管、抄作业、自动肉鸽等 |
| ⏱️ | **定时任务** | 现有方舟任务可定时启动；新增引擎需另核对调度入口接线 |
| 🔄 | **自动更新** | 应用与方舟沿用现有更新入口；边狱任务页提供资源下载、检查和更新 |

## 运行要求

| 项目 | 要求 |
|---|---|
| 系统版本 | Android 9+（API 28） |
| 权限方案 | [Shizuku](https://shizuku.rikka.app/) 已运行并授权，或设备已 Root |
| 设备架构 | arm64-v8a 或 x86_64 |

## 文档

| 文档 | 说明 |
|---|---|
| [架构说明](docs/zh-cn/develop/ARCHITECTURE.md) | 当前模块边界、运行与资源生命周期、方舟迁移范围 |
| [新增游戏指南](docs/zh-cn/develop/ADDING_A_GAME.md) | 引擎契约、宿主接线、任务页、资源安装与验证步骤 |
| [构建指南](docs/zh-cn/develop/BUILDING.md) | 从源码构建 APK |
| [外部自动化集成](docs/zh-cn/develop/AUTOMATION.md) | 通过 Intent / am 命令与 MacroDroid、Tasker 联动 |
| [Roadmap](docs/zh-cn/develop/ROADMAP.md) | 功能规划与进度 |
| [PR 规范](docs/zh-cn/develop/PULL_REQUEST_GUIDELINES.md) | 提交 PR 前的标题、描述、验证与评审约定 |
| [第三方代码声明](docs/zh-cn/develop/THIRD_PARTY_NOTICES.md) | 引用的开源组件及许可证 |

## 参与贡献

欢迎提交 Pull Request！无论是修复 Bug、优化体验还是实现新功能，我们都非常感谢。

1. Fork 本仓库
2. 创建你的分支 (`git checkout -b feat/your-feature`)
3. 提交更改 (`git commit -m 'feat: 添加某某功能'`)
4. 推送到远程 (`git push origin feat/your-feature`)
5. 发起 Pull Request

> 提交信息请遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范（`feat:`、`fix:`、`docs:` 等）。
> 首次构建请参阅 [构建指南](docs/zh-cn/develop/BUILDING.md)。
> 提交 PR 前请阅读 [PR 规范](docs/zh-cn/develop/PULL_REQUEST_GUIDELINES.md)。

如果觉得项目有用，欢迎点一个 Star ⭐ 让更多人看到！

## 与上游项目的关系

请注意以下几点，以免误解：

- **本项目不是 MaaAssistantArknights 的官方 Android 端**，也不隶属于其开发团队。本项目通过 JNA 加载上游发布的 `libMaaCore.so` 预编译产物来驱动明日方舟，遇到问题请先在本仓库反馈，不要向上游提。
- **边狱公司引擎移植自 LixAssistantLimbusCompany**，是其 AGPL-3.0 衍生作品，同样与上游开发者无隶属关系。资源包内的流水线定义、模板素材与 ONNX 模型取自上游；Python 动作和桌面配置语义由 Kotlin 实现，具体移植范围见[第三方代码声明](docs/zh-cn/develop/THIRD_PARTY_NOTICES.md)。
- 两个上游项目均以 AGPL-3.0 发布，本项目沿用同一许可证。
- 游戏内素材、图标与商标归各自权利人所有；上游代码的开源许可证不等同于这些素材的独立授权。

## 致谢

- [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) — 明日方舟游戏小助手，基于图像识别技术
- [LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany) — 边狱公司助手，本项目边狱引擎的移植来源
- [AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany) — 边狱公司助手，资源清单协议与 Android 键码映射的设计参考
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — Display and control your Android device.
- [Shizuku](https://github.com/RikkaApps/Shizuku) — Using system APIs directly with adb/root privileges from normal apps through a Java process started with app_process.

## 许可证

本项目以 [AGPL-3.0](LICENSE) 许可证发布。第三方代码保留其原始许可证，详见[第三方代码声明](docs/zh-cn/develop/THIRD_PARTY_NOTICES.md)。
