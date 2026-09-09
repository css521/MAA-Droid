# 第三方代码声明

本项目以 [AGPL-3.0](../../../LICENSE) 发布。以下列出移植代码、资源来源和主要原生运行库；第三方内容保留各自许可证。依赖版本以 [版本目录](../../../gradle/libs.versions.toml) 和各模块的构建声明为准，升级依赖时需同步核对其许可证。

## scrcpy

- **项目地址**：[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- **版权**：Copyright 2018 Genymobile
- **许可证**：[Apache License 2.0](../../../LICENSE-Apache-2.0)
- **原始代码**：[server/src/main/java/com/genymobile/scrcpy](https://github.com/Genymobile/scrcpy/tree/master/server/src/main/java/com/genymobile/scrcpy)
- **本项目中的位置**：[`core/bridge/src/main/java/com/aliothmoon/maadroid/third/`](../../../core/bridge/src/main/java/com/aliothmoon/maadroid/third/)

### 用途

这些代码用于在 Shizuku / Root 提权服务进程中构造 Android `Context`，并通过反射访问 Android Hidden API，实现虚拟显示器管理、输入事件注入、屏幕信息获取等功能。

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
| `third/wrappers/DisplayControl.java` | Android 14+ 的 DisplayControl 反射适配，读取物理显示器 ID 与令牌 |
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
- **使用方式**：通过 [setup_maa_core.py](../../../scripts/setup_maa_core.py) 下载预编译产物（`libMaaCore.so`、其依赖库及资源文件），运行时由 JNA 动态加载。
- **桥接位置**：[engine/arknights/core](../../../engine/arknights/src/main/java/com/aliothmoon/maadroid/engine/arknights/core) 与该模块的 [AIDL](../../../engine/arknights/src/main/aidl/com/aliothmoon/maadroid)。

当前脚本的下载目标已改为 `engine/arknights/src/main/assets/MaaSync/MaaResource` 和 `engine/arknights/src/main/jniLibs/<abi>`。资源 manifest 插件、MaaCore 版本字段及生成 JNI 目录的任务也由[方舟模块构建声明](../../../engine/arknights/build.gradle.kts)持有；APK 内前缀仍为 `MaaSync/MaaResource`，运行时仍沿用现有资源更新和投递链路。这些路径说明当前代码与打包归属；完整 APK 的兼容性仍需构建和设备验证。方舟任务配置、业务回调和面板尚未全部迁入引擎模块。

---

## LixAssistantLimbusCompany

- **项目地址**：[LixAssistantLimbusCompany](https://github.com/HSLix/LixAssistantLimbusCompany)
- **许可证**：本地核对 LALC v5.0.0，commit `431b432e22f0b0da08b95d7c478fa213be20b3e8` 的 [LICENSE](https://github.com/HSLix/LixAssistantLimbusCompany/blob/431b432e22f0b0da08b95d7c478fa213be20b3e8/LICENSE)，为 GNU AGPL v3。
- **使用方式**：资源复用与代码移植，范围如下。

### 一、资源直接复用（不修改）

`engine/limbus` 通过 [LimbusResourcePack](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus/LimbusResourcePack.kt) 声明上游固定提交的源码 ZIP；安装器只提取以下目录并校验文件。上游 Python 源码只在暂存目录作为动作声明文本检查，安装完成前删除，不在 Android 上执行。

| 上游路径 | 内容 |
|---|---|
| `lalc_backend/config/task/` | 声明式任务流水线 |
| `lalc_backend/config/language/` | 语言相关的关键词与饰品名表 |
| `lalc_backend/img/` | 识别模板与 UI 素材；`zh` / `en` 同名文件按语言选择 |
| `lalc_backend/ai/model/` | 三个 ONNX 分类模型（mirror_legend / mirror_path / skill_icon） |
| `lalc_backend/recognize/models/` | OCR 检测与识别模型 |

执行资源和 UI 图鉴共用这份下载资源，不依赖本仓库 Release 或额外图鉴下载源。[LimbusCatalog](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus/ui/LimbusCatalog.kt) 从 `img/general/ego_gifts`、`img/general/theme_packs` 和中文饰品语言表生成图鉴；罪人及星光图片也从 `img/general` 读取。APK 已移除原来复制的批量 PNG 与 `catalog.json`。缺资源时显示下载提示，用户配置独立保存；同一路径更新后按 manifest revision 刷新图鉴和图片。

[sync_limbus_ui.py](../../../scripts/sync_limbus_ui.py) 现仅检查本地资源并可导出诊断 JSON，不向 APK 复制图鉴。[pack_engine_resource.py](../../../scripts/pack_engine_resource.py) 是离线打包工具；其中旧的 Release feed 描述不是当前 App 的下载协议。当前链路以 `LimbusResourcePack` 和宿主 `EngineResourceService` 为准。

游戏画面与素材的权利仍属于原权利人。LALC 的 README 也将网图来源图标排除在代码开源声明之外，不能把 AGPL 代码许可扩大解释为所有图片或模型的独立授权。

### 二、代码移植（衍生作品）

以下路径相对于 [Limbus 引擎源码目录](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus)。移植代码保留 LALC 的 AGPL v3 许可；上游路径相对于其仓库：

| 本仓库 | 移植自上游 |
|---|---|
| `pipeline/PipelineNode.kt` | `lalc_backend/workflow/task_node.py` 的节点模型与字段语义 |
| `pipeline/PipelineRegistry.kt` | `lalc_backend/workflow/task_registry.py` 的 `init_tasks()` 六步装配与引用校验 |
| `action/*` | `lalc_backend/workflow/task_execution.py` 与 `lalc_backend/task_action/*` 的动作语义 |
| `recognize/Recognizer.kt` | `lalc_backend/recognize/img_recognizer.py` 的识别接口 |
| `ui/LimbusWorkspace.kt`、`ui/LimbusTeamsPage.kt` | `lalc_frontend/lib/pages` 中任务、队伍、卡包及工作日志页的信息与交互 |
| `config/LimbusWorkspaceConfig.kt` | `lalc_frontend/lib/managers/config_manager.dart` 的配置结构、`lalc_backend/server.py` 的配置转换规则 |

流水线字段和已移植动作保留上游语义；桌面配置通过 `LimbusWorkspaceConfig` 转换为执行配置。资源热更不能新增 Kotlin 尚未实现的动作，兼容检查失败时需升级 App，不能据此宣称所有上游逻辑都能免发版更新。

---

## AhabAssistantLimbusCompany

- **项目地址**：[AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany)
- **许可证**：本地核对 V1.5.2-beta.70，commit `4a32dc8e1c659b94eafabab3d76f65aec7efde05` 的 [LICENSE](https://github.com/KIYI671/AhabAssistantLimbusCompany/blob/4a32dc8e1c659b94eafabab3d76f65aec7efde05/LICENSE)，为 GNU AGPL v3。
- **记录的引用范围**：设计参考；当前边狱执行资源和动作移植来源为 LALC。

现有来源注释记录了资源清单、Android 按键与屏幕规格方面的参考：

| 上游路径 | 本仓库中的对应位置 |
|---|---|
| `module/resource_sync/manifest.py` | [pack_engine_resource.py](../../../scripts/pack_engine_resource.py) 的 `path` / `sha256` / `size` 清单设计说明 |
| `module/automation/input_handlers/simulator/simulator_control.py` | [KeyMap.kt](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus/action/KeyMap.kt) 的按键参考说明 |
| `module/game_and_screen/screen.py` 及素材目录布局 | [LimbusProfile.kt](../../../engine/limbus/src/main/java/com/aliothmoon/maadroid/engine/limbus/LimbusProfile.kt) 的显示规格参考说明 |

AALC 的素材、字体和模型未被选为当前资源下载源。其跨平台目录布局本身不能证明 LALC 模板在 Android 上的识别效果；分辨率、语言、画面差异及输入行为仍需实机验证。

## 原生运行库

下列版本已对照本地缓存中的对应 Maven POM；JNA AAR 内 `classes.jar!/META-INF/LICENSE` 也声明了双许可证。许可证链接指向相应版本。

| 组件与当前依赖 | 许可证 | 实际用途与声明位置 |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` | [MIT，Microsoft Corporation](https://github.com/microsoft/onnxruntime/blob/v1.19.2/LICENSE) | [边狱](../../../engine/limbus/build.gradle.kts)和[方舟](../../../engine/arknights/build.gradle.kts)模块均声明同一严格版本；边狱的 `OnnxClassifier`、`recognize/ocr/PpOcrEngine` 使用 Java/JNI API，MaaCore 使用 C API |
| `org.opencv:opencv:4.11.0` | [Apache-2.0](https://github.com/opencv/opencv/blob/4.11.0/LICENSE) | [engine/limbus/build.gradle.kts](../../../engine/limbus/build.gradle.kts)；图像预处理、模板和特征匹配 |
| `net.java.dev.jna:jna:5.18.1`，AAR 形式 | [Apache-2.0 OR LGPL-2.1-or-later](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE)，上游允许二选一 | [engine/arknights/build.gradle.kts](../../../engine/arknights/build.gradle.kts)；`core/MaaCoreLibrary.java`、`core/AsstApiCallback.java` 与 MaaCore C 接口互调 |

MaaCore 下载产物还含 `libopencv_world4.so` 等上游原生依赖，它们与边狱声明的 `org.opencv:opencv:4.11.0` AAR 是不同产物，不能用 AAR 的版本推断 MaaCore 内部 OpenCV 的版本。ONNX Runtime 的库许可与上游模型文件的许可属于不同内容。OpenCV、JNA 及 MaaCore 预编译产物所含其他组件也保留各自声明，不能因本项目使用 AGPL 而改写其许可证。

MaaCore 与 Java/JNI 可能同时依赖 `libonnxruntime.so`。[PrepareMaaNativeLibrariesTask](../../../build-logic/src/main/kotlin/com/aliothmoon/maadroid/buildlogic/PrepareMaaNativeLibrariesTask.kt) 在生成打包目录前检查 MaaCore、其他依赖库和 `libonnxruntime4j_jni.so` 与选定 AAR 中运行库的符号兼容性；验证后从生成目录排除重复的 ORT 库，保留原始下载文件。不能用随意 `pickFirst` 替代该检查，也不能将编译声明的版本当作任意上游 MaaCore 二进制均兼容的保证。
