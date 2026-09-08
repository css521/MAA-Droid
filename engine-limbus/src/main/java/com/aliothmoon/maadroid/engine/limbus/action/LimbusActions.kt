package com.aliothmoon.maadroid.engine.limbus.action

/**
 * 边狱动作总装。
 *
 * 上游 LALC 靠 `import task_action` 触发装饰器副作用完成注册；Kotlin 没有这种
 * 隐式时机，故显式列一处。**这里注册了什么，决定了资源包兼容门闸放不放行**
 * （`manifest.required_actions - ActionRegistry.names()` 非空即拒绝装载）。
 *
 * 上游 v5.0.0 的 45 个动作名里，35 个需要实现体、10 个是纯路由（无实现体，
 * 由 [com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRunner] 直接放过）。
 */
object LimbusActions {

    @Volatile
    private var registered = false

    /** 幂等：多次调用只注册一次，便于每个入口都放心调 */
    @Synchronized
    fun install() {
        if (registered) return
        BaseActions.registerAll()
        UtilActions.registerAll()
        BattleActions.registerAll()
        EventActions.registerAll()
        LuxcavationActions.registerAll()
        MirrorActions.registerAll()
        registered = true
    }

    /** 仅测试用：允许重新装配 */
    internal fun resetForTest() {
        registered = false
    }
}
