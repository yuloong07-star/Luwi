package io.github.yuloong07star.luwi.channel

/**
 * 「首次拿到特权通道之后替主人开一次无障碍」该做什么 (2.7.9 第 7 条)
 *
 * 主人 2026-10-19 的口径是"默认允许 dshd 通过无障碍读取屏幕内容", 但**只替他开一次**: 开过之后他
 * 自己关掉, 那就是他的选择 —— 第二档记号 (`accessibility-user-off`) 把那条记下来, 之后启动不再替他开,
 * 否则"关掉"只关到下次启动
 *
 * 单独抽出来的理由与别处那些纯函数一样: 这一条在模拟器上量不了 (没有 root / Shizuku, 通道起不来),
 * 而它错一档的样子是"每次启动都替他打开"或者"永远开不了", 两种都很安静
 */
internal object AccessibilityDefault {

    /** 这一趟该做什么 */
    enum class Act {
        /** 记号已经定过 (替他开过, 或他自己关过): 什么都不做 */
        NOTHING,

        /** 已经开着: 只把"替他开过"的记号补上, 免得下一趟再算一遍 */
        MARK_ONLY,

        /** 还没开, 也没定过: 替他开一次 */
        ENABLE,
    }

    fun decide(running: Boolean, autoMarked: Boolean, userOff: Boolean): Act = when {
        autoMarked || userOff -> Act.NOTHING
        running -> Act.MARK_ONLY
        else -> Act.ENABLE
    }
}
