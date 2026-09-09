# 边狱巴士页面移植对照

对照版本：LALC v5.0.0（431b432e22f0b0da08b95d7c478fa213be20b3e8）、AALC V1.5.2-beta.70。以本地上游源码核对字段和执行读取点，用户截图中的 AALC V0.4 仅作界面参考。

## 两个上游的区别

| 内容 | LALC | AALC | Android 的选择 |
|---|---|---|---|
| 页面组织 | 任务、工作日志、队伍、主题卡包 | 一键长草、队伍、主题卡包、小工具、设置 | 边狱游戏标签下的任务、队伍、卡包、日志 |
| 队伍 | 20 槽位；罪人点击顺序；任务选择队伍后轮换 | 队伍体系、商店策略、额外战斗/观测策略 | 采用 LALC 槽位和顺序，不能混用 AALC 的配置键 |
| 镜牢 | 流派、饰品允许/排除、融合/购买/强化、技能替换、星光、路径权重 | 另有售卖策略、弃用体系、观测饰品、更多逐层行为 | 实现 LALC 的选项；未给 AALC 独有策略放无效开关 |
| 执行结构 | JSON 流程和 Python handler | Python 任务与独立配置模型 | Kotlin 移植 LALC handler，加载原 JSON 流程 |
| 更新源 | HSLix/LixAssistantLimbusCompany | KIYI671/AhabAssistantLimbusCompany | 仅下载 LALC 自动化资源；不混用 AALC 图片和模型 |

来源代码：LALC `lalc_frontend/lib/pages/{task_page,team_config_page,theme_pack_page,work_page}.dart`、`managers/config_manager.dart`、`lalc_backend/server.py`；AALC `app/{farming_interface,team_setting_card,theme_pack_setting_interface}.py` 与 `assets/config/config.example.yaml`。

## 当前 Android 页面

入口：后台任务 → 边狱巴士。页面、图鉴、配置转换均在 `engine/limbus`，通过 `EngineUi.workspace` 装配，宿主不引用罪人、队伍或镜牢类型。

| 页面 | 已接入的内容 |
|---|---|
| 任务 | 邮件、经验、纺锤、镜牢、奖励；执行次数、关卡、进入/跳过战斗、队伍选择顺序；E.G.O、每日狂气兑换、结束游戏 |
| 镜牢任务设置 | 难度、保留货币、融合饰品、替换技能与购买饰品、强化、领奖、七类节点权重 |
| 队伍 | 20 槽位、名称、复制配置、12 罪人图片与出战顺序、流派、全体治疗 |
| 星光与开局 | 10 星光及基础/+ /++ 等级、初始饰品选择顺序 |
| 饰品 | 332 张上游图鉴；中文/英文名称搜索；流派筛选、首选流派、单件允许/排除 |
| 技能替换 | 每位罪人独立开关，1→3、2→3、1→2 的优先级 |
| 卡包 | 95 个上游主题卡包图文、搜索、权重及排序 |
| 日志 | 当前会话最近 500 条信息/警告/错误，可复制 |
| 配置 | 自动保存、JSON 复制导出、合并形态的 LALC JSON 导入；旧五开关页面的选中状态、关卡、领奖选项迁移 |

配置格式沿用上游 `taskConfigs`、`teamConfigs`、`themePackWeights`。桌面各自独立的配置文件需要合成该顶层结构后导入；不把桌面 ZIP 配置集当作已支持。手机不提供关闭电脑、Windows 进程路径或 WebSocket 服务器设置；运行结束可保持或结束游戏。

上游页面有 `battle_fail_handle` 字段，但当前 v5.0.0 的 `server.py` 转换函数未传给引擎。Android 没有把它做成可编辑的无效选项。AALC 的额外配置需要单独实现动作后才能加入。

## 配置到执行

`LimbusWorkspaceConfig` → 五个上游配置分节 → `EngineTaskStore` 原样保存 → `appendTask` → 运行时同一配置快照。

- 队伍以所选顺序生成 `team_indexes`、`team_orders` 和镜牢平行数组，保留 1–20 的游戏槽位，不按槽位重排。
- 任务次数覆盖检查节点的 `target_count`；检查计数控制重复和禁用，下一次运行不继承禁用状态。
- 路线读取 `mirror.node_scores`；卡包读取 `theme_pack.<名称>.weight`。保留旧 Android 扁平配置的读取兼容。
- 任务取消/结束先停止引擎，再释放模型、帧映射、显示和资源版本锁。
- 资源下载可在同页手动启动；首次执行也会安装。固定提交首次安装无需 GitHub tags API；检查更新才访问 API，失败保留旧资源。
- 用户配置存 DataStore，和可替换资源目录分开，更新不覆盖队伍/策略。图鉴优先读取已安装资源，APK 自带图片供下载前配置。

## 验证和待验证

配置保存重载、上游数组转换、技能编码、卡包/路线权重、旧配置迁移、检查节点计数/子链调度、资源原子替换及设备会话生命周期均有 JVM 测试。真实 LALC v5.0.0 源码归档已通过 647 个资源文件的安装和 SHA-256 校验。

当前没有连接 Android 真机：尚未完成页面在手机上的视觉/触控验收，也没有证明登录、OCR、选队、战斗、镜牢全程可用。移动端布局使用 Compose，未直接运行桌面的 Flutter/PyQt 页面。
