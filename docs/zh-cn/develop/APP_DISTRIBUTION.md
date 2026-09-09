# 独立 APP 的 APK 分发与自更新

APK 更新源由构建参数指定，不读取 Git remote，不根据开发者用户名、应用名或资源上游推断仓库。仓库默认不附带任何 APK 更新源。此配置只影响宿主 APK，不影响方舟 MAA 引擎、MaaResource 或边狱 LALC 资源更新。

## 构建参数

在现有构建命令中传入以下 `-P` 属性，或将同名键写入仓库根目录的 `local.properties`。`-P` 优先；显式传空字符串会清除本地同名配置。debug/release 使用同一套规则。参数随 APK 编译，安装后不能通过 CDK 或设置页改写。

| 属性 | BuildConfig 字段 | 默认值 / 用途 |
| --- | --- | --- |
| `maa.appUpdate.githubOwner` | `APP_UPDATE_GITHUB_OWNER` | 空；发布仓库的用户或组织名 |
| `maa.appUpdate.githubRepo` | `APP_UPDATE_GITHUB_REPO` | 空；发布仓库名，不含 owner 或 URL |
| `maa.appUpdate.mirrorChyanRid` | `APP_UPDATE_MIRROR_CHYAN_RID` | 空；可选，独立 APK 在 MirrorChyan 注册的 RID |

例如，在 `local.properties` 中配置（占位值必须替换成发布者确认的真实值）：

```properties
maa.appUpdate.githubOwner=YOUR_GITHUB_OWNER
maa.appUpdate.githubRepo=YOUR_APK_REPOSITORY
# 只有为此独立 APK 注册并核对过的 RID 才能填写；不用 MirrorChyan 就留空。
maa.appUpdate.mirrorChyanRid=
```

等价命令参数为 `-Pmaa.appUpdate.githubOwner=YOUR_GITHUB_OWNER -Pmaa.appUpdate.githubRepo=YOUR_APK_REPOSITORY`。这里不提供任何用户仓库的猜测值。owner/repo 必须同时提供合法名称；不能放 URL、斜杠、查询参数或片段。

## 检查与下载行为

- owner/repo 缺失、只填一个或格式不合法：APK 检查返回现有 `UpdateCheckResult.Error`，原因说明构建配置缺失/无效；不请求 GitHub 或 MirrorChyan。下载返回失败及 `UpdateProcessState.Failed`，在链接解析、APK 缓存读取和安装前停止。单独填写 RID 或 CDK 不能启用 APK 更新。
- owner/repo 合法、RID 为空或无效：APK 版本检查直接走自己的 GitHub。稳定渠道读 `/releases/latest`；测试渠道读最近 100 条 release，并按现有版本比较器选择最高版本。GitHub 下载可用；选择 MirrorChyan 下载会明确提示配置问题及改用 GitHub，不会套用原项目 RID。
- owner/repo 与 RID 均合法：版本检查沿用 MirrorChyan，GitHub 下载仍指向显式仓库。MirrorChyan 检查和下载共用同一 RID，下载仍需 CDK。MirrorChyan 失败直接返回原错误，不自动换仓库或 RID。
- 启动自动检查、CDK 变化检查、手动检查均经 `UpdateService.checkAppUpdate`；启动自动下载、手动下载和换源重试均经 `downloadApp`。设置页沿用已有检查错误/下载失败展示。启动检查沿用原先忽略错误的行为，不额外弹窗；用户手动检查可查看禁用原因。

GitHub 发布沿用现有 APK 资产约定：release 应使用语义化版本 tag（如 `v1.2.3` 或 `1.2.3`），并包含文件名以 `universal.apk` 结尾的 APK。检查结果保留 tag 原文；下载先查原 tag，不带 `v` 且返回 404 时仅在同一仓库尝试加 `v`。缺少 APK、GitHub HTTP 错误或响应解析失败均返回错误，不改用旧 APP。测试渠道仅扫描第一页 100 条 release，未加入私有仓库鉴权。

发布者须保证 GitHub release 与 MirrorChyan RID 都分发此独立 APP、版本一致，且 APK 的 applicationId、签名与升级版本号符合 Android 安装要求。本次来源配置不增加签名校验、迁移旧 APP 或改写发布 workflow。

## 与原项目和资源上游的边界

MAA 引擎地址、`MaaAssistantArknights/MaaResource`、MirrorChyan `MaaResource` 和 LALC 的原上游保持不变。它们不是 APK 分发源。原项目致谢链接保留；公告、文档、反馈和已有静态公告里的链接也不由这些构建参数改写。若独立发行需要替换这些内容，应另行处理，不能用 APK 源配置覆盖资源源。

## 集成验证

新增测试覆盖配置缺失/非法、端点构造、GitHub/MirrorChyan 路由、无配置零网络调用、GitHub 渠道及 APK 资产、MirrorChyan RID/CDK，以及服务层下载门禁。测试使用固定的示例仓库和模拟响应，不访问线上更新服务。

本次已通过独立 Kotlin 2.4.10 编译与 Java 17 / JUnit 4 运行的 20 个测试。验证复用本机缓存依赖和既有模块产物，新增 BuildConfig 字段由临时测试夹具提供；这不代表 AGP 生成字段或完整 Android 构建已验证。测试类为 `AppUpdateSourceConfigTest`、`ConfiguredAppVersionCheckerTest`、`AppUpdateEndpointsTest` 和 `UpdateServiceAppSourceTest`。

整合时请运行相关 JVM 单元测试并完成 Android 构建、真机手动检查和安装验证；本次开发不运行 Gradle、不提交、不启动 workflow。
