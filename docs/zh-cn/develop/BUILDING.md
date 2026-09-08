# 构建指南

## 环境准备

- 安装 [Eclipse Temurin JDK 25](https://adoptium.net/zh-CN/temurin/releases?version=25)

- 安装 [Android Studio](https://developer.android.com/studio)

- 下载 MAA Core 预编译产物（so 库 + 资源文件）

  ```bash
  python scripts/setup_maa_core.py
  ```

  下载慢时装 `aria2c`（`brew install aria2`），脚本会自动改用它多连接下载。实测单个 ABI 约 176 MB：直连单流 321 KB/s，aria2c 8 连接约 920 KB/s。**不要找 GitHub 加速镜像** —— 实测 ghproxy.net 只有 29 KB/s，gh-proxy.com 与 ghfast.top 返回 403，hub.gitmirror.com 不可达，都比直连更差。想强制用内置下载器时设 `MAA_SETUP_DOWNLOADER=urllib`。

  遇到 `403 rate limit exceeded` 是匿名调 GitHub API 被限流，用 `export GITHUB_TOKEN=$(gh auth token)` 即可。

  本地只编 arm64 时加 `--abi arm64-v8a` 可省一半下载量（`app/build.gradle.kts` 里非 CI 默认也只编 arm64-v8a）。

  MAA 发布包里的 `libMaaAndroidNativeControlUnit.so` 取自 MaaFramework 最新正式版，可能落后于应用依赖的特性（如多点触控需要 >= v5.13.0-beta.3）。需要时用 `--maafw-tag` 指定 MaaFramework 版本替换：

  ```bash
  python scripts/setup_maa_core.py --maafw-tag v5.13.0-beta.5
  ```

## 构建步骤

- 使用 Android Studio 打开此文件夹，在 Settings - Build, Execution, Deployment - Build Tools - Gradle - Gradle Projects - Gradle JDK 选择此前安装的 temurin-25

- 运行 Sync Project with Gradle Files，Android Studio 将自行安装其他依赖，完成后运行 Assemble app Run Configuration 即可构建apk。
