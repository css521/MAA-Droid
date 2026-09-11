package com.maadroid.app.engine.arknights.constant

/**
 * 明日方舟侧的外部地址：MAA 官方 API 与 MaaResource 资源源。
 *
 * 与宿主自身的地址（App 自更新、公告、FAQ）分开放，见 `constant/AppApi.kt`。
 * 原先两者混在一个 `MaaApi` 对象里，把它整块搬进引擎模块会让宿主的**自更新**
 * 反向依赖方舟引擎 —— 语义上讲不通，也会被边界契约的计数暴露出来。
 *
 * 教训记在这里：判断「某文件属于哪个引擎」不能只看它有没有 app 内部 import。
 * 这个文件的 import 依赖为零，但内容一半是宿主的。
 */
object ArknightsApi {

    /** MAA 官方 API 主备地址 */
    const val MAA_API = "https://api.maa.plus/MaaAssistantArknights/api/"
    const val MAA_API_BACKUP = "https://api2.maa.plus/MaaAssistantArknights/api/"

    val API_URLS = listOf(MAA_API, MAA_API_BACKUP)

    /** 活动关卡 */
    const val STAGE_ACTIVITY_API = "gui/StageActivityV2.json"

    /** 任务配置 */
    const val TASKS_API = "resource/tasks.json"

    /** 全球服的 tasks.json 按渠道分路径 */
    fun getGlobalTasksApi(clientType: String): String =
        "resource/global/${clientType}/resource/tasks.json"

    // ---- MaaResource（方舟资源包）的两个源 ----
    //
    // 长期看这两个地址该由 ResourcePackSpec 自己持有 —— 现在宿主的更新服务按
    // 引擎遍历资源包，却仍从这里取方舟专属的 URL，这是遗留耦合。

    const val MIRROR_CHYAN_RESOURCE = "https://mirrorchyan.com/api/resources/MaaResource/latest"

    const val GITHUB_RESOURCE =
        "https://github.com/MaaAssistantArknights/MaaResource/archive/refs/heads/main.zip"

    /** 基建排班协议文档，面板里的说明链接 */
    const val BASE_SCHEDULING_SCHEMA =
        "https://maa.plus/docs/zh-cn/protocol/base-scheduling-schema.html"
}
