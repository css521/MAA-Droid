# 边狱安卓适配 · 交接文档

交接日期：2026-09-10。当前分支 `main @ 9f8adc3`，工作树干净，`:engine:limbus` 408 用例全绿。
可实测产物：`out/apk/MAA-Droid-765-arm64-v8a.apk`（Thread 链路已真机验证通过）。

---

## 一、下一步要做的：App 内开发模式 · 自动采集素材底片

### 为什么

适配安卓的**唯一真实阻塞**是缺「这台设备的真帧」。上游 LALC 的素材来自 Steam 客户端，
在安卓上大面积失配；而判断某张素材该怎么改、改成什么，必须看识别器**实际参与匹配的那一帧**。

不要用聊天/截图渠道传来的图片当依据——那条链路（`1280x720 渲染 → 手机屏 → 截屏 → 压缩 → 还原`）
会磨掉小素材依赖的细节，系统性压低所有分数。**本次交接前已因此连续判断错三次**（见第四节）。

### 设计（不需要碰 Compose UI）

| 项 | 做法 |
|---|---|
| 开关 | `BuildConfig.DEBUG`，或读标记文件 `Maa/debug/limbus/.capture`（建/删文件即开关，便于真机现场切换） |
| 采集点 | `LimbusRecognizer` 里每次 `frames.grab()` 之后。该处本就在取帧，零额外开销 |
| 去重 | 缩到 64×36 灰度求帧间差，只存与上一张明显不同的。否则走一遍主页会得到几千张重复 |
| 命名 | `Maa/debug/limbus/frames/<seq>_<当时识别的节点或素材名>.png`。**名字里必须带节点名**，否则导出后分不清哪张是队伍页、哪张是关卡页 |
| 上限 | 60 张左右，满即停，避免写满存储 |
| 落盘位置 | 必须在 `.../files/Maa/debug/` 这棵树下——日志导出器已在收集它（导出的 zip 里能看到
`infrast/enter_facility/*_raw.png`）。**同时把绝对路径写进 `onDiagnostic` 日志**，导出器万一没收就能手动取 |

`LimbusEngine` 里推导目录的辅助函数（沿资源目录向上找名为 `Maa` 的祖先，找不到退到同级 `debug`）
本次已写过，可从 git 历史里的失败尝试中取，或直接重写，只有十几行。

### 必须按这个顺序改，因为它同时能定位一个未查明的问题

**本次交接前尝试过两次，都失败，且根因未查明。** 症状：给 `LimbusRecognizer` 加东西之后，
编译报一批 `Unresolved reference 'toMat' / 'warned' / 'templateOf'`——全是该类**后面**声明的成员
在**前面**的方法里解析不到。第一次用 Python 脚本盲替换，怀疑花括号被破坏；第二次改用 Edit
工具逐处精确插入（构造参数、函数、调用点、companion 常量），**错误依然完全相同**，
说明「脚本破坏结构」的推测是错的，真正原因另有其他。

未验证的怀疑方向：`tooling/ksp-processor` 对该类的处理、或给主构造器添加带默认值的
`File?` 参数触发了什么。**这一步值得查明而不是绕开**，因为它会挡住此后所有对该类的改动。

所以：

```bash
rm -rf engine/limbus/build          # gradlew clean 清不掉 intermediates/built_in_kotlinc
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sh gradlew :engine:limbus:testDebugUnitTest --no-build-cache    # 基线，应 408 绿
```

1. **只**加构造参数 `private val captureDir: File? = null,` → 立刻
   `sh gradlew :engine:limbus:compileDebugKotlin`
   - **若此步就报那批 unresolved → 根因就是「给这个类加构造参数」，这才是要查的真问题**
   - 若干净，继续
2. 加采集函数（含去重、上限）→ 再编译
3. 加调用点 → 再编译
4. `LimbusEngine` 里传 `captureDir` → 编译 + 跑测试
5. 打包、真机走一遍主页→镜牢→关卡页→战斗，导出日志

### 做完之后回路就闭合了

```
真机走一遍 → 导出日志(含帧) → scripts/audit_templates.py 离线跑
→ 拿到失配清单 → 从真帧裁替代素材 → audit 再验(几秒) → 打包实测
```

**只有最后一步需要真机。** 这是从「一轮几十分钟」到「一轮几秒」的关键。

---

## 二、已确立的结论（都有数据支撑，不要重新推导）

### 1. OCR 类识别在安卓上没问题，模板类识别全部失配

| 类型 | 状态 | 证据 |
|---|---|---|
| OCR（关卡编号、`12/12`、饰品名、Lv 标签） | **可用** | `60`→`G0` 字形还原修完后 Thread 链路真机通过 |
| 模板匹配（`details`、`skip_battle`、`skill_*`…） | **全灭** | 10 张流水线判据实测 0.555~0.820，无一过 0.85 阈值 |

`details` 与真实控件 **负相关 −0.143**，从帧里裁出的区域自匹配 0.951 —— 说明匹配流水线本身
正常（CLAHE + 模糊 + `TM_CCOEFF_NORMED` 无问题），**是素材和控件根本不是一回事**。

流水线实际引用的模板**只有 50 个**（562 张里大部分是饰品/卡包/罪人头像，供动作代码按名查）。
范围比想象小得多。

### 2. 画面几何没有问题，不存在「一个缩放系数修全部」的捷径

真机 `frame.geometry` 实测：`内容区=0,0 1280x720`，满幅无黑边。那些 `1264x720`/`1276x720`
是画面本身边缘偏暗被 `threshold=8` 判成黑，不是 letterbox（letterbox 会是稳定、对称、成块的）。

**因此缩放假设作废，`screenshotScale` 一刀修 562 张的方案不存在。**（该阈值偏敏感，
若保留这个诊断建议提到 16~24。）

### 3. AALC 的素材不能直接换，它的识别风格不同

AALC（`/Users/css521/project/java/AhabAssistantLimbusCompany`）确实支持模拟器
（`module/automation/input_handlers/simulator/mumu_control.py`），素材来自安卓客户端，基准 2560×1440，
自带归一化（`1440/set_win_size`，坐标同步换算）。**但：**

- **没有 `details`、没有 `skip_battle`** —— 它的流程不靠这些判据
- 489 张素材里 **256 张是 2560×1440 整帧**（`*_assets.png`），因为它是「裁区域 + 与整帧参考比」，
  不是「小模板 + 全图搜索」
- 实测一张小素材 `thread_continuous_combat_show_box` 在降质帧上得 0.967（同条件下 LALC 素材全灭），
  证明其素材与安卓客户端几何相容——**它的价值是当底片，不是当替换文件**

**结论：无论用谁的逻辑，都必须从安卓画面裁出正确模板。没有自动手段**——哪一块是 `details`
是语义判断。而底片应优先用自己设备的真帧（AALC 的是别人 MuMu 上某个版本的画面）。

### 4. 不要全改成 AALC 逻辑

`app/` 13065 行（PySide GUI，全弃）、`tasks/` **8088 行 27 个模块**（核心流程，须重写 Kotlin）、
`module/automation` 5626 行，实际要搬约 **15000 行**；而 `engine/limbus` 现有 16324 行已跑通
EXP 全链、Thread 已验证。

**更关键**：AALC 流程是 Python 代码，MAA-Meow 选 LALC 正是因为它的流程在 JSON 里可热更
（见 `LimbusEngine` 类注释）。改造成 AALC 逻辑会让「上游每次更新都要改代码发 APK」
从担忧变成必然，而且**仍然要做同样的裁素材工作**。

值得借的三样（都可增量加，不用推倒）：基准分辨率归一化、区域裁剪+整帧比对（作为小模板的
补充判据——17×17 的 `skill_blunt` 在无技能界面上得分反而更高，这种小图注定不可靠）、256 张整帧当底片。

### 5. MaaFramework：方向对，但现在不是时候

平台化（多游戏共用后端、资源即数据、`roi` 相对定位、节点 `timeout` 天然消掉自环）它都对，
而且模块骨架已就位（`settings.gradle.kts` 的注释写明「加一个游戏 = 加一个 engine 目录」）。

**闸门是实的**，`scripts/setup_maa_core.py --maafw-probe v5.13.0` 跑出的确定清单：

```
同名 6 个，全部大小不同（不能两份共存，必须选一份）：
  libopencv_world4.so               60.8MB vs 23.7MB   ← 差 2.6 倍，最硬的一处
  libonnxruntime.so                 26.6MB vs 25.1MB
  libfastdeploy_ppocr.so            21.9MB vs 21.6MB
  libMaaUtils.so                    14.2MB vs 14.2MB   ← 同尺寸，很可能同源，非障碍
  libMaaAndroidNativeControlUnit.so  2.8MB vs  2.9MB
  libc++_shared.so                   1.7MB vs  8.9MB   ← MAA 那份未 strip
仅 MaaFramework 有 9 个共 98MB，其中 libMaaFramework.so 49.7MB
```

OpenCV 差 2.6 倍是因为 MaaFramework 的 world4 编进了 FeatureMatch 需要的 `xfeatures2d` 等模块。
**下一步是符号核对**（`llvm-readelf -d` 看 NEEDED、`llvm-nm -D --undefined | grep cv::` 看需要哪些符号，
与 MAA 那份 `--defined-only` 求差集），差集为空则可复用 23.7MB 那份、零体积增长。

**但它修不了当前的 bug**——素材还是错的。应等第一节做完、有了正确行为基线，
再拿它跑同一条链做对照。

补充：`libMaaAndroidNativeControlUnit.so` 和 `libMaaUtils.so` **本来就是 MaaFramework 的产物**，
随 MAA 发布包进来的；项目已有 `override_maafw_control_unit`（`scripts/setup_maa_core.py:286`）
从 MaaFramework release 取 .so。所以约束是**版本对齐**而非名字冲突——从同一个 release zip
多取几个 .so 即天然一致。这比「从源码重建改 SONAME」便宜一个数量级。

### 6. APK 285MB 的构成（减体积别从 OpenCV 下手）

```
250.1 MB / 9329 个   assets/MaaSync/MaaResource   ← 明日方舟全客户端资源，59%
 85.5 MB /   35 个   *.dex                        ← debug 不跑 R8
 76.8 MB /   13 个   lib/ native（含两份 OpenCV 47MB）
  3.1 MB            assets/shizuku.apk
```

顺序应是：① 方舟资源按客户端按需下载 ② release + R8 ③ 最后才是 OpenCV 统一。前两项占 80%，都不碰 native。

---

## 三、可用的工具

| 工具 | 用途 |
|---|---|
| `scripts/audit_templates.py` | **离线**素材审计：把上游素材逐张打进真机原帧，输出失配清单。复现了 Kotlin 侧 `TemplateMatcher` 的完整流水线，分数与真机可比（同素材同帧：本机 0.669 / 设备 0.652）。支持 `--scales` 多尺度、`--expect` 映射、`--csv` |
| `scripts/setup_maa_core.py --maafw-probe TAG` | MaaFramework 与 MaaCore 的 native 库共存清单，纯分析不改构建 |
| `.maa-cache/converter-env/bin/python3` | 项目自带的 venv，有 cv2 5.0.0 + numpy，离线实验都用它 |
| `scripts/verify_native_runtime.py` | 打包后核对 ELF 依赖与 ORT 版本化符号，每次出包都应跑 |

---

## 四、本次交接前犯过的错，不要重复

1. **拿聊天渠道的截图当原帧量像素**（两次）。先推出「上下黑边、UI 缩到 0.8 倍」，
   又据此断言 MaaFramework 的整帧 resize 能修当前 bug。**两个结论都作废。**
   → 只信识别器实际参与匹配的那个 Mat。
2. **只用一个数据点就宣称离线回路已验证**。`details` 在本机与设备都失败所以看起来一致，
   实际那条回路对**能命中**的素材完全不可信（`main_drive_no_text` 本机 0.555，设备当场识别成功——
   因为它走的是 `AndroidHomeNavigation` 那条自带缩放的旁路）。
3. **用 Python 脚本盲替换 Kotlin 类体**。改坏后回滚，且**未查明根因**就归因于花括号，
   后来用 Edit 精确插入仍复现同样错误，说明归因是错的。
   → 改 Kotlin 一处一编译；`rm -rf engine/limbus/build`，不要指望 `gradlew clean`。
4. **把「加可观测性」判断成浪费**。曾说「在快要删掉的代码上加诊断是浪费」——错的，
   当前引擎的诊断数据是验证任何迁移正确性的**对照基准**。

---

## 五、建议的总体路线

1. **开发模式采集底片**（第一节）← 阻塞项
2. 从真帧裁替代素材，按任务链推进（EXP✅ / Thread✅ / 镜牢 → ），每条链只处理它用到的那几张
3. 加**基准分辨率归一化层**：坐标与素材统一过换算，之后换分辨率、混用不同基准素材都不改代码
   （这是「不要写死代码」的正解；`screenshotScale` 参数已存在，AALC 的 `1440/set_win_size` 是参考实现）
4. 加区域裁剪+整帧比对，作为小模板的补充判据
5. 有了正确行为基线之后，才评估 MaaFramework 作为平台后端

**不要做**：全改 AALC 逻辑；现在动 MaaFramework；为省体积先动 OpenCV；无据修改坐标或素材。

---

## 六、素材覆盖：机制已建，但**还没接到设备上**

### 已完成

`engine/limbus/overlay/` 是本仓库自带的素材覆盖层，`scripts/pack_engine_resource.py`
打包时会用它盖掉上游同名素材（产出时打印 `[覆盖] img/en/ui/details.png`）。
已放入一张从真帧裁出的安卓版 `details.png`（124x42，自匹配 0.983，与次高峰余量 +0.440）。

### 但这条路到不了设备 —— 关键事实

**App 不下载预打好的资源包，它直接下载 LALC 的 GitHub 仓库归档：**

```kotlin
// LimbusResourcePack.kt
override val upstreamArchive = UpstreamArchive(
    repository = "HSLix/LixAssistantLimbusCompany",
    initialRevision = ResourceRevision("v5.0.0", "431b432e…"),
    resourcePrefix = "lalc_backend",
)
override val bundledAssetPrefix: String? = null
```

所以 `pack_engine_resource.py` 是 **CI/校验工具，不是运行时路径**。覆盖必须在
**设备安装时**生效。

### 正确的接入点

`AtomicResourceInstaller.kt:83`：解包完成、激活之前调用
`pack.finalizeUpstreamInstall(staging, revision)`。**这就是该插入覆盖的位置** ——
它作用在 staging 目录上，失败不会破坏已装资源。

障碍：`finalizeUpstreamInstall` 拿不到 `Context`/`AssetManager`，而覆盖素材要从
APK assets 里读。建议做法（三处小改，都向后兼容）：

1. `engine/api` 的 `ResourcePackSpec` 加 `val overlayAssetPrefix: String? get() = null`
   —— 带默认值，现有实现与 4 个测试替身都不受影响
2. `AtomicResourceInstaller` 在调用 `finalizeUpstreamInstall` **之前**，把
   `assets/<overlayAssetPrefix>/**` 覆盖到 staging
3. `LimbusResourcePack` 设 `overlayAssetPrefix`，覆盖素材放
   `engine/limbus/src/main/assets/<prefix>/img/en/ui/details.png`

注意 `assets/lalc/ui/README.md` 说的「不要在这里重新提交批量 PNG」指的是几百张
饰品/卡包图鉴，不是这几 KB 的覆盖素材，两者不冲突。

同一份覆盖素材应同时留在 `engine/limbus/overlay/`（供 CI 打包与离线审计）和 APK
assets（供设备安装），或让打包器从 assets 目录取，避免两处维护。

### 选素材的原则（实测得出）

**看与次高峰的余量，不能只看自匹配分数。** 5 个候选实测：

```
仅 Details 文字      70x24  自匹配 0.976  余量 +0.236
含 « 与按钮边框      112x34  自匹配 0.963  余量 +0.392
按钮整块+留白       124x42  自匹配 0.983  余量 +0.440  ← 采用
下方小 Details        62x20  自匹配 0.919  余量 +0.001  ← 反例，且最佳位置落在错处
下方小 Details+边框   76x32  自匹配 0.953  余量 +0.187
```

小图会被噪声淹没——这也解释了为什么 17x17 的 `skill_blunt` 在没有技能的界面上
得分反而更高。素材应带上按钮边框等结构，宁大勿小。

### 真帧上的确切数据（推翻了之前基于压缩截图的结论）

上游素材**并非全废**，真帧实测有 4 张本来就好用：

```
inferno 0.954 ✅   luxcavation 0.951 ✅   reward_coin 0.930 ✅   clear_all_caches 0.910 ✅
```

真正要换的（界面对了但素材不行）：

```
main_drive_no_text   0.491    main_window_no_text 0.502    main_drive_with_text 0.533
quit_game            0.508    charge_enkephalin   0.623    resume               0.647
details              0.739    skip_battle         0.755    pass_missions        0.823
```

主页导航那三个真帧只有 0.49~0.53，而设备日志显示它们识别成功——因为走了
`AndroidHomeNavigation` 那条自带缩放的旁路。**旁路一直在替素材背锅。**

分数低但**不是问题**的（界面根本不对，采集恰好发生在识别失败时）：
`network_is_unstable` 0.254、`server_error_*` 0.304、`server_is_under_*` 0.333、
`dungeon_enter` 0.336。

### 待验证

`details` 的**跨队伍泛化未验证**——只有一张队伍页真帧。压缩截图上得 0.697 不作为
反证（那些图 2.17 宽高比被压成 16:9，命中点 y 一致而 x 偏 42px）。下次采到另一套
队伍的真帧后需复核。

---

## 七、根治办法：文字判据走 OCR（已实施 19 个节点）

### 为什么这才是根治

50 个流水线判据里约 35 个是**文字标签**，而两个客户端显示的是**同一串文字**。
OCR 读字不读像素，字体渲染、按钮底纹、UI 缩放的差异都不影响它。

同一个按钮在真机原帧上的对比：

```
模板匹配（上游素材）   0.485    全图峰值 0.739 还落在卡牌美术上
模板匹配（真帧重裁）   0.983    可用，但跨队伍泛化未验证
OCR                   conf 1.00
```

**OCR 那条不需要任何素材**，所以上游更新、游戏改版、换设备都免疫 —— 这也解释了
为什么 Thread 的 60→G0 修完就一直好着。逐张重裁素材是打补丁，去掉素材依赖才是根治。

### 三处机制

1. `PipelineNode.RECOGNITION_OCR`：用 `params.text` 而非 `template`。
   校验强制 text 非空 —— 空串会让 findText 恒命中，节点变成无条件通过，比识别不中更危险。
2. `PipelineRegistry.load(files, patches)`：**节点级字段补丁**，顶层覆盖 + `params` 深合并。
   不是整文件替换（那会挡掉上游后续的流程改动）。补丁名打错会被装载校验拒绝。
3. 安装时覆盖层：`AtomicResourceInstaller` 在 `finalizeUpstreamInstall` **之前**把
   APK assets 的覆盖层盖到 staging。顺序关键 —— finalize 要校验的是最终内容。

补丁文件 `config/task-patch.json`，缺失即纯上游行为。

### 关键：期望文字不需要真机帧

**直接对上游素材本身做 OCR** 就能拿到实际显示文字 —— 素材就是那段文字的裁图。
50 张里 22 张读出文字，取读得稳的子串即可。

**长句不给 mask**（串足够独特），**短串必须给 mask**：实测 `dungeon_enter` 期望
"Enter" 时全屏 OCR 匹配到了警告句里的「…fficulty to enter.」。

### 离线验证环境（这是不必反复真机测的关键）

```bash
uv venv /tmp/ocrenv --python 3.12
uv pip install --python /tmp/ocrenv/bin/python onnxruntime rapidocr-onnxruntime opencv-python-headless
# 模型取自资源包，与设备同一套
unzip -o pack.zip "recognize/models/*" -d /tmp/ocrmodels
```

用它可以：把素材 OCR 成文字、在真帧上验证区域（区域外涂黑后重跑）、
核对不会误撞别处文字。**本机 0.669 / 设备 0.652 已验证同口径。**

### 已转 19 个节点

带 mask 的短串（6）：`exp/thread/mirror_choose_team`、`mirror_ready_to_battle`（Details）、
`touch_to_start`（Clear all caches）、`mirror_enter_resume`（Resume）。

无 mask 的长句（13）：6 个 `error_*` 弹窗、`mirror_select_encounter_reward_card`、
`mirror_select_event_effect`、`mirror_select_floor_ego_gift2`、`event_pass_check`、
`mirror_enter_last_week`、`mirror_choose_star`、`mirror_defeat`。

### 刻意不转的

`connecting`、`charge_enkephalin`、`luxcavation`（美术字，且模板 0.951 好用）、
`no_mail_in_storage`、`download_data`、`server_error_occurred_try_again`（OCR 跨行乱序，
无可靠子串）、`dungeon_enter`（短串需区域，缺验证帧）。
`inferno`(0.954) / `luxcavation`(0.951) / `reward_coin`(0.930) / `clear_all_caches`(0.910)
模板本来就好用，不动以免回归。

### 一个差点上线的 bug

补丁文件加 `_comment` 注释键（JSON 无注释，但这个文件需要写清为什么这么改）后，
它的值是数组，`jsonObject` 会抛异常，继而被 `runCatching` 吞掉 ——
**所有补丁一起静默失效**，症状是"改了完全没用"。现在解析时过滤下划线前缀并有用例钉住。

### 剩下的图标判据

约 15 个（`skip_battle` 17x17、`red_exclaimation`、`main_drive_*`、`win_rate`、
`legend`、`skill_*`…）仍需模板，且必须是安卓源的。这是**有界的小集合**，
覆盖层机制正好承载。选素材看**与次高峰的余量**而非只看自匹配分数
（62x20 的反例自匹配 0.919 但余量仅 0.001 且命中错位置 —— 小图会被噪声淹没）。

---

## 八、离线可做的部分已穷尽 —— 剩余项的硬边界

### 已完成（不需要真机验证）

- **21 个文字判据转 OCR**，不再依赖任何素材
- **3 张导航图标**用本设备真帧重裁：上游 0.491/0.502/0.533 → 0.984/0.977/0.978

### 穷尽过程（三次尝试，都留下可复用的判据）

**1. 逐行 OCR 全量重扫 50 个素材**（先前只在 10 个上重试过）。
只多读出两个，且两个都不可用：

- `server_error_occurred_try_again` → 逐行仅「occurred」可靠，太泛；
  误命中会触发无谓重试
- `win_rate` → 逐行仅「Rate」（4 字符）。它服务 `battle_winrate`、
  `battle_winrate_click`、`mirror_battle` 三个战斗流程关键判据，
  误命中会在错误时机按 p。**没有验证过的区域就转，风险大于收益**

**2. 用上游模板在 AALC 帧上定位再裁 —— 逻辑循环，失败。**
`skip_battle` 裁出「Mirror Dungeon」文字碎片，`skill_blunt` 裁出一团火焰。
用一个匹配不上的模板去定位它自己要匹配的元素，定位分数 0.845/0.876 本身就说明
位置不可信。**「与次高峰余量 +0.4」只证明这块图独特，不证明它是对的东西**——
看了实物才发现。自匹配与余量都不能替代「确认裁的是正确元素」。

**3. AALC 的 `*_assets.png` 是掩码帧**（只留目标区域、其余涂黑，其比对方式就是
保留区域涂黑其余），理论上直接给出元素与精确位置。但实测口径不符：

- `win_rate` 裁出 20x23，在无关界面上匹配 0.936 → 会误命中，被检查拦下
- `event_skip` 裁出 182x72 而上游 92x54，宽差 2x 高只差 1.3x → 包围盒与上游
  不是同一块区域
- 且 `legend` 0.977 / `event_skip` 0.965 这些上游素材在 AALC 安卓帧上本来就好用，
  **替换只会引入风险，不换才是对的**

### 剩余项：缺输入，不缺方法

约 11 个图标判据（`skill_blunt/pierce/slash`、`win_rate`、`skip_battle`、`legend`、
`red_exclaimation`、`pack_search`、`loading_tips`、`owned_ego_resources`、`event_skip`）
需要**本设备**的战斗页 / 镜牢九宫格 / 商店真帧：

- 别人模拟器的掩码帧尺寸口径对不上
- 上游模板无法自定位（见上面第 2 条）
- 素材 OCR 读不出可用文字（见第 1 条）

4 个文字判据（`charge_enkephalin`、`no_mail_in_storage`、`connecting`、
`server_error_occurred_try_again`）已定性为不可转，原因见第七节。

**采集只需一次**：开发模式默认开启（debug 构建），走到战斗页、镜牢九宫格、商店即可，
按素材名去重、跨运行不重复。之后裁素材与验证全程离线（`scripts/audit_templates.py`
+ 第七节的离线 OCR 环境），不再需要往返。

---

## 九、第三轮真机反馈（maa_logs_20260910_222436 + 230944）

### 已修

| 问题 | 根因 | 修法 | 提交 |
|---|---|---|---|
| 编队太快进不了战斗 | 上游 0.5 秒是 PC 量，安卓列表有惯性 | `LIST_SETTLE` 0.5→1.2，点完加 `TEAM_LOAD` 1.5 | `733d92b` |
| 速度太慢 | 邮箱每次 4 遍 OCR≈4 秒，不在邮箱页也跑满 | 大区域一遍 → null 则补小区域 → 仍 null 即返回 | `733d92b` |
| 主页重复点击 | `check_enkephalin` inverse 自环 post_delay=1 不够 | 补丁 post_delay→2.5 | `8c1cea4` |
| 事件勾选罪人无反应 | **我引入的回归**：13 个未验证的 OCR 转换把 `event_pass_check` 换坏 | 只保留真帧验证过的 8 个补丁，其余回退 | `5858f00` |

### 未修（定位完成，下一轮处理）

**选队伍 15 选错** —— `ChooseTeamAction` 用 `teamNo/6` 算滑动次数、`teamNo%6` 算点击格位，
假设一次滑 6 项。安卓有惯性可能多滑。
可靠修法：OCR 读队伍名或队伍编号定位，不数滑动次数——但需要先确认配置里有没有名字字段。

**选星光不生效** —— `ChooseStarAction` 的坐标 `STAR_POSITIONS` 是 PC 九宫格布局
（(200,190), (400,190)…），安卓上九宫格可能不在那个位置。需要九宫格界面的真帧来量——
目前采集帧里没有（该界面的识别素材 `grace_of_the_star_of_names_and_spiders` 没被
`templateMatch` 求值到，所以没有触发采集）。

### 核心教训（第三次犯同一个错）

**不要把未在真帧验证过的改动推上设备。** 那 13 个长句 OCR 转换的期望文字是对上游素材
猜出来的，`event_pass_check` 被换坏导致事件卡死。这个错误比识别失败更糟——
识别失败只是走兜底分支，**我引入的改动让流程完全无法推进**。

规则：**每一个补丁节点，都必须在真帧上验证过「取字区域内确实读到期望文字、且不误撞
别处」之后才能上设备。** 没有例外。
