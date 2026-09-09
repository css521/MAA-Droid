UI 图鉴不再随 APK 内置。

`LimbusCatalog` 从已安装 LALC 资源的 `img/general/ego_gifts`、
`img/general/theme_packs` 和 `config/language/zh/ego_gifts.json` 生成图鉴；
罪人、星光也直接读取同包中的图片。安装器原有的 `img`、`config/language`
白名单已包含这些文件，无需额外下载源或 `catalog.json`。

页面存活期间检查已安装 manifest 的 revision，版本变化同时刷新图鉴和图片。
`rememberLimbusCatalog` 预留 `updateKey`，宿主以后可传入资源更新 key 提前刷新。
用户权重、饰品偏好和队伍配置不由图鉴加载器改写。

开发时可运行 `python3 scripts/sync_limbus_ui.py /path/to/LALC` 检查本地素材，
或用 `--out /tmp/lalc-catalog.json` 导出诊断 JSON。脚本不会写入 APK assets，
也不会改写已安装资源目录。不要在这里重新提交批量 PNG。
