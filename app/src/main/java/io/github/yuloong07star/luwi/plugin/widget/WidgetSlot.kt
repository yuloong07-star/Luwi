package io.github.yuloong07star.luwi.plugin.widget

/**
 * 宿主槽的名字表 (方案 3.1)
 *
 * 桌面上那一格必须由**主 APK** 的清单声明 (`AppWidgetProvider` 是编译期的东西, 运行时装不出来),
 * 于是"插件往桌面加东西"这件事的默认路径变成: 主 APK 预置几个**槽**, 插件只往里填内容
 *
 * **槽是稀缺的, 同槽互斥** —— 一个槽同一时刻只挂一只插件, 这是产品决策不是缺陷:
 * 想两只同时上就得加槽, 而加槽要发一次版
 *
 * 2.7.8 批次 6 加了第三个槽 [CHAT], 而它与前两个**不一样**: 它不由插件填内容 ——
 * 立绘取当前浮标外观 (`BallTheme` 那个当前脸), 气泡取 `$DSH_HOME/lw/desktop-chat.json`。
 * 于是"挂哪一只插件"这件事对它没有意义, [PLUGIN_FILLED] 就是这条界线
 */
internal object WidgetSlot {

    /** 动画槽: ViewFlipper 帧序列, 给"会动的形象"那一类 */
    const val ANIM = "anim"

    /** 图文槽: 一张图 + 标题 + 正文 + 最多两个按钮, 给番茄钟 / 状态卡 / 快捷入口 */
    const val IMAGE = "image"

    /**
     * 对话槽 (2.7.8 批次 6): 右下角立绘 + 最近几句气泡 + 一颗「说一句」
     *
     * 它**不挂插件**: 内容一半来自当前浮标外观, 一半来自那份 `desktop-chat.json`, 两个来源都不在
     * "插件目录"里。所以重画它不必问 `.widgets.json`, 也不必选插件
     */
    const val CHAT = "chat"

    /** 全部宿主槽 (重画 / 开机补授权都按这一份走) */
    val ALL = listOf(ANIM, IMAGE, CHAT)

    /** 由插件往里**填内容**的那几个槽 (设置页那几行、`WidgetSpec` 收的 slot 都是这几个) */
    val PLUGIN_FILLED = listOf(ANIM, IMAGE)

    /** 槽位那几行在设置页里显示的顺序也是它 */
    fun known(slot: String): Boolean = slot in ALL

    /** 这一个槽是不是由插件填 (chat 不是) */
    fun pluginFilled(slot: String): Boolean = slot in PLUGIN_FILLED
}
