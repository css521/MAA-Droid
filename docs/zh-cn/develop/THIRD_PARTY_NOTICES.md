# 第三方代码声明

本项目包含来自以下开源项目的代码，按各自原始许可证进行分发。

## scrcpy

- **项目地址**：[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- **版权**：Copyright 2018 Genymobile
- **许可证**：[Apache License 2.0](../../../LICENSE-Apache-2.0)
- **原始代码**：[server/src/main/java/com/genymobile/scrcpy](https://github.com/Genymobile/scrcpy/tree/master/server/src/main/java/com/genymobile/scrcpy)
- **本项目中的位置**：[`app/src/main/java/com/aliothmoon/maadroid/third/`](../../../app/src/main/java/com/aliothmoon/maadroid/third/)

### 用途

这些代码用于在 Shizuku 用户服务进程中构造 Android `Context`，并通过反射访问 Android Hidden API，实现虚拟显示器管理、输入事件注入、屏幕信息获取等功能。

### 包含文件

| 文件 | 说明 |
|------|------|
| `third/FakeContext.java` | 伪造的 Android Context，用于在无 Activity 的进程中获取系统服务 |
| `third/Ln.java` | 日志工具，同时输出到 Android Logger 和标准输出 |
| `third/Workarounds.java` | Android 系统兼容性处理，构造 ActivityThread 和 Looper |
| `third/Command.java` | Shell 命令执行工具 |
| `third/IO.java` | I/O 工具类 |
| `third/Size.java` | 尺寸数据类 |
| `third/DisplayInfo.java` | 显示器信息数据类 |
| `third/wrappers/ServiceManager.java` | Android ServiceManager 反射封装，获取各系统服务实例 |
| `third/wrappers/DisplayManager.java` | DisplayManagerGlobal 反射封装，管理显示器信息和虚拟显示器 |
| `third/wrappers/InputManager.java` | InputManager 反射封装，注入输入事件 |
| `third/wrappers/WindowManager.java` | IWindowManager 反射封装，管理旋转、显示尺寸、IME 策略等 |
| `third/wrappers/ActivityManager.java` | ActivityManagerNative 反射封装，获取 ContentProvider、启动 Activity |
| `third/wrappers/PowerManager.java` | IPowerManager 反射封装，查询屏幕状态 |
| `third/wrappers/StatusBarManager.java` | IStatusBarService 反射封装，控制通知栏和快捷设置面板 |
| `third/wrappers/SurfaceControl.java` | SurfaceControl 反射封装，管理物理显示器令牌和电源模式 |

### 主要修改

以下是相对于 scrcpy 原始代码的主要修改：

- 调整包名从 `com.genymobile.scrcpy` 至 `com.aliothmoon.maadroid.third`
- 移除了与屏幕录制、视频编码、音频采集相关的代码，仅保留系统服务反射封装部分
- 新增 `ActivityManager`、`SurfaceControl`、`StatusBarManager`、`PowerManager` 等封装类
- `DisplayManager` 增加了 `createNewVirtualDisplay()` 方法，用于创建独立虚拟显示器
- `WindowManager` 增加了 `captureDisplay()`、`setForcedDisplaySize()`、`clearForcedDisplaySize()` 等方法
- `Ln` 的日志 TAG 和前缀修改为本项目标识

---

## MaaAssistantArknights

- **项目地址**：[MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights)
- **许可证**：[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)
- **使用方式**：通过 `scripts/setup_maa_core.py` 下载预编译产物（`libMaaCore.so` 及资源文件），运行时由 JNA 动态加载

---

## LixAssistantLimbusCompany

- **项目地址**：[LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany)
- **许可证**：[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)
- **使用方式**：两部分

### 一、资源直接复用（不修改）

`engine/limbus` 的资源包从上游固定提交的源码归档提取，也可由 `scripts/pack_engine_resource.py` 离线重打包。使用以下五部分：

| 上游路径 | 内容 |
|---|---|
| `lalc_backend/config/task/` | 声明式任务流水线（v5.0.0 为 10 个文件 133 个节点） |
| `lalc_backend/config/language/` | 语言相关的关键词与饰品名表 |
| `lalc_backend/img/` | 模板素材（622 个 PNG，563 个唯一基名；`zh`/`en` 同名文件是语言变体） |
| `lalc_backend/ai/model/` | 三个 ONNX 分类模型（mirror_legend / mirror_path / skill_icon） |
| `lalc_backend/recognize/models/` | OCR 检测与识别模型 |

执行资源在 App 内按 LALC 的固定 commit 下载、校验并安装，不依赖本仓库 Release。`engine/limbus/src/main/assets/lalc/ui` 内的罪人、星光、饰品和卡包 PNG 直接复制自 LALC v5.0.0；`catalog.json` 由其目录和中文语言表生成，包含来源 commit。`scripts/sync_limbus_ui.py` 可从本地上游更新此内置图鉴。游戏画面及素材的权利仍属于原权利人。

### 二、代码移植（衍生作品）

`engine-limbus` 的引擎逻辑移植自上游 Python 实现，属 AGPL-3.0 衍生作品：

| 本仓库 | 移植自上游 |
|---|---|
| `pipeline/PipelineNode.kt` | `workflow/task_node.py` 的节点模型与字段语义 |
| `pipeline/PipelineRegistry.kt` | `workflow/task_registry.py` 的 `init_tasks()` 六步装配与引用校验 |
| `action/*` | `workflow/task_execution.py` 与 `task_action/*` 的动作语义 |
| `recognize/Recognizer.kt` | `recognize/img_recognizer.py` 的识别接口 |
| `ui/LimbusWorkspace.kt`、`ui/LimbusTeamsPage.kt` | `lalc_frontend/lib/pages` 中任务、队伍、卡包及工作日志页的信息与交互 |
| `config/LimbusWorkspaceConfig.kt` | `managers/config_manager.dart` 的配置结构、`lalc_backend/server.py` 的配置转换规则 |

> 移植保持了上游的字段名与语义，目的是让上游改动能经资源包热更直接生效、无需转换层。

---

## AhabAssistantLimbusCompany

- **项目地址**：[AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany)
- **许可证**：[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)（本地核对版本 V1.5.2-beta.70）
- **使用方式**：**仅作设计参考，未复制代码或素材**

借鉴了以下三处设计思路：

- `module/resource_sync/manifest.py` 的资源清单协议形状（`schema_version` / 清单级稳定标识 / `files[]` / `packages[]`，条目含 `path` + `sha256` + `size`）
- `module/automation/input_handlers/simulator/simulator_control.py` 的 Android keycode 映射（`enter`→66、`esc`→111、`p`→44），据此确认边狱 Android 客户端接受硬件按键事件
- `module/game_and_screen/screen.py` 的多分辨率归一化思路

其素材树只按 UI 主题与语言分目录、无 pc/android 之分却同时驱动 Steam 端与模拟器内的 Android 端，这一事实支撑了「边狱两端共用一套 UI 布局、上游 PC 端模板可直接用于 Android」的判断。
