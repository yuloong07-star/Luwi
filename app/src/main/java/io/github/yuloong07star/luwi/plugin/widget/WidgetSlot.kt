package io.github.yuloong07star.luwi.plugin.widget

/**
 * 宿主槽的名字表 (方案 3.1)
 *
 * 桌面上那一格必须由**主 APK** 的清单声明 (`AppWidgetProvider` 是编译期的东西, 运行时装不出来),
 * 于是"插件往桌面加东西"这件事的默认路径变成: 主 APK 预置几个**槽**, 插件只往里填内容
 *
 * **槽是稀缺的, 同槽互斥** —— 一个槽同一时刻只挂一只插件, 这是产品决策不是缺陷:
 * 想两只同时上就得加槽, 而加槽要发一次版
 */
internal object WidgetSlot {

    /** 动画槽: ViewFlipper 帧序列, 给"会动的形象"那一类 */
    const val ANIM = "anim"

    /** 图文槽: 一张图 + 标题 + 正文 + 最多两个按钮, 给番茄钟 / 状态卡 / 快捷入口 */
    const val IMAGE = "image"

    val ALL = listOf(ANIM, IMAGE)

    /** 槽位那几行在设置页里显示的顺序也是它 */
    fun known(slot: String): Boolean = slot in ALL
}
