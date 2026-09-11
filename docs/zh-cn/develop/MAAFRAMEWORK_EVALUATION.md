# MaaFramework 与 MaaFwApp 接入评估

评估日期：2026-09-10。状态：源码评估，尚未集成到本项目 APK。

核对版本：MaaFramework `00356a68c1ef7864b1adbdfbcf86da9017f2a61e`；
MaaFwApp `f4f6f220e21e3a1b7b0cf5df4bdbe0ec04c668f7`；LALC v5.0.0。
未构建 MaaFramework、未验证发布二进制兼容性，也未在 Android 设备上运行该接入方案。

## 判断

MaaFramework 对本项目有用，适合作为后续游戏共用的执行后端。它已有 Android
原生控制、识别、任务调度、自定义动作和资源装载能力。MaaFwApp 提供了更直接的
Kotlin/JNA 集成参考。两者都不能原样执行 LALC 的 JSON 和 Python 动作，
也不会自动修正桌面与手机游戏界面的差异。

建议保留现有宿主和明日方舟 MaaCore，在独立模块验证 MaaFramework 后端。
LALC 是否迁移，应由原始流水线的行为对照和真机结果决定。不要仅为统一名称
把现有 LALC 执行器再包一层，然后声称已迁移到 MaaFramework。

## 能复用什么

| 需求 | 已确认能力 | 本项目仍需负责 |
| --- | --- | --- |
| Android 本机执行 | NDK 构建支持 arm64-v8a、x86_64，最低 API 23 | APK 打包、权限、设备会话和 native 兼容验证 |
| 截图与触控 | AndroidNativeController 与 CustomController | 接入已有虚拟显示，正确处理帧生命周期、坐标和取消 |
| 通用识别与任务 | 模板、OCR、特征、神经网络、Custom 回调、等待和错误路由 | LALC 动作适配、模型预处理对照、手机页面差异 |
| 游戏配置页面 | ProjectInterface 声明 task、option、group、setting、多语言 | Compose 渲染适配与 LALC 特有队伍、卡包配置映射 |
| 更新资源 | 装载 bundle/pipeline/image，覆盖节点与图片 | 下载进度、内容 SHA 校验、兼容门闸、失败回滚和运行期间资源锁 |
| 后续增加游戏 | 原生采用 MaaFramework/PI 的资源可共用后端 | 校验资源支持 Android，提供其依赖的 custom action/agent 运行环境 |

源码依据：

- [Android 构建预设](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/CMakePresets.json#L55)
- [Controller C API](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/include/MaaFramework/Instance/MaaController.h#L55)
- [CustomController 回调](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/include/MaaFramework/Instance/MaaCustomController.h#L31)
- [Resource C API](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/include/MaaFramework/Instance/MaaResource.h#L30)
- [ProjectInterface 协议](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/docs/zh_cn/3.3-ProjectInterfaceV2协议.md)

## LALC 不能直接装入的原因

LALC 使用 `recognition: "template_match"`、`action: "exp_select_stage"`，
参数放在 `params`，延迟以秒计；Framework 有自己的识别/动作枚举、参数结构与毫秒单位。
只改字段名不能完成兼容转换。

还需逐项对照 LALC 的共享计数、check、节点禁用、中断返回、重试和结束语义。
Framework 会在 next 未命中时循环到超时；LALC 是不同的栈式路由机制。
例如 LALC 经验本 next 最后有一个无条件的“不能跳过”错误节点：原样转换后，
它仍会立即命中，Framework 的等待机制不会自动修复这一兜底分支。

`choose_team`、`ready_to_battle`、镜牢决策等 Python 动作不是通用 Click。
必须注册相应 Custom 动作，或移植到可在 Android 运行的受支持运行环境。
LALC 的 Windows 输入实现也不能因为 Framework 支持 Agent 就直接在手机执行。

模板图片可以沿用上游，但匹配预处理、OCR、模型输入输出仍须验证。页面布局不同时，
换执行器不能消除 Android 适配工作。更新流水线和图片与更新原生库、动作代码是
两类升级；不能承诺任意新增上游动作无需升级 APK。

依据：[LALC 任务代码](https://github.com/HSLix/LixAssistantLimbusCompany/tree/v5.0.0/lalc_backend/workflow)、
[经验本流水线](https://github.com/HSLix/LixAssistantLimbusCompany/blob/v5.0.0/lalc_backend/config/task/luxcavation.json)、
[Framework 流水线协议](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/docs/zh_cn/3.1-任务流水线协议.md)。

## MaaFwApp 取舍

可复用或参考其 [Kotlin/JNA 声明](https://github.com/css521/MaaFwApp/blob/f4f6f220e21e3a1b7b0cf5df4bdbe0ec04c668f7/app/src/main/java/com/maadroid/maafw/maa/MaaFrameworkLibrary.kt)、
[MaaRunner 装载与绑定顺序](https://github.com/css521/MaaFwApp/blob/f4f6f220e21e3a1b7b0cf5df4bdbe0ec04c668f7/app/src/main/java/com/maadroid/maafw/remote/MaaRunner.kt)。
其 JNA 声明尚未覆盖这里需要的 CustomController 和 custom-action 注册接口。
Framework 文档标记外部 Java 绑定停留在旧 v3，不能直接假定其支持当前 ABI。

该版本 MaaFwApp 的 [集成说明](https://github.com/css521/MaaFwApp/blob/f4f6f220e21e3a1b7b0cf5df4bdbe0ec04c668f7/INTEGRATION.md#L53)
以 APK 内置资源为主，不支持资源热切换；其停止请求与路径复用策略也不等同于本项目的
停止确认和资源 revision 锁定。因此保留当前 EngineSession、设备 lease、资源安装器和
预览生命周期。复制代码时记录来源，保留 MaaFwApp 的 AGPL-3.0 和 Framework 的 LGPL-3.0 声明。

## 接入前的实际障碍

1. **设备边界。** 当前 `libbridge` 的三个导出名称和结构与 AndroidNativeControlUnit
   对应，但仍使用进程全局帧。现有 DispatchInputMessage 尚未处理 STOP_GAME、INPUT，
   默认返回 0，而 Framework 将其理解为成功。不能直接将此路径作为完整的多游戏控制器。
   首个验证应以 CustomController 接现有 DeviceHandle，保留会话归属和停止确认。
2. **同步回调与内存。** Framework 回调是同步 C ABI，FrameSource.grab 是 suspend；
   需要专门的执行线程和取消策略。MaaImageBufferSetRawData 没有 stride 参数，必须按帧的
   行距拷贝为紧凑 BGR，并保证回调和图像存活时间，不可保存已经被下一帧覆盖的 ByteBuffer。
3. **同名 native 库。** MaaCore 与 Framework 都涉及 MaaUtils、AndroidNativeControlUnit、
   OpenCV、ORT、libc++。不能用 pickFirst 掩盖版本差异；分进程也不会消除 APK 内同名库冲突。
   必须检查实际产物的 SONAME、导入符号、版本化符号和 ORT API，再决定统一构建或隔离命名。
4. **更新校验。** Framework 的资源 hash 聚合文件大小，不能替代本项目的内容 SHA 校验。
   继续使用暂存、兼容检查与安装机制；停止当前任务并确认释放后再切换资源。

对应代码：[现有桥接分发](../../../core/bridge/src/main/native/bridge_input.cpp)、
[当前 native 兼容检查](../../../build-logic/src/main/kotlin/com/maadroid/app/buildlogic/NativeRuntimeCompatibility.kt)、
[Framework 图像输入](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/source/Common/MaaBuffer.cpp#L258)、
[资源 hash 实现](https://github.com/MaaXYZ/MaaFramework/blob/00356a68c1ef7864b1adbdfbcf86da9017f2a61e/source/MaaFramework/Resource/ResourceMgr.cpp#L185)。

## 最小验证顺序

以下为建议，尚未实现：

1. 在独立 `runtime/maafw` 模块验证实际 Android 库与 MaaCore 共存，再适配 CustomController。
   先验证截图、一次输入、取消、停止确认和保留预览，不改两个正式引擎的执行路径。
2. 用小型原生 Framework pipeline 验证任务与事件，再用一条 LALC 经验本路径验证转换。
   对照同一份上游资源的节点、动作参数、计数和停止结果；不能只比较是否编译成功。
3. 验证 PI 任务配置渲染和一次资源更新/回滚，仍由本项目资源中心安装。
4. 真机确认完整路径后，再决定迁移 LALC 哪些通用实现；新游戏优先共用该后端。

依赖关系保持 `engine/<game> → runtime/maafw → engine/api`，宿主负责组装。
这是候选结构，不代表新增模块已存在；当前真实模块见 [ARCHITECTURE](ARCHITECTURE.md)。
