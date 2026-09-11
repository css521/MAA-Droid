package com.maadroid.app.engine.arknights.enums
enum class InfrastRoomType {
    /** 制造站 */
    Mfg,

    /** 贸易站 */
    Trade,

    /** 控制中心 */
    Control,

    /** 发电站 */
    Power,

    /** 会客室 */
    Reception,

    /** 办公室(+速公招那个) */
    Office,

    /** 宿舍 */
    Dorm,

    /** 加工站(合精英材料) */
    Processing,

    /** 训练室 */
    Training;

    companion object {
        val values = InfrastRoomType.entries
    }
}
