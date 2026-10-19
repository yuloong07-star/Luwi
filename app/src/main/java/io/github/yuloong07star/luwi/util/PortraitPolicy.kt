package io.github.yuloong07star.luwi.util

/**
 * 手机锁竖屏那一档的判据 (2.7.9 第 9 条)
 *
 * **为什么不在清单里写** `android:screenOrientation="portrait"`: 那一条会把 API 33~35 的平板也一起
 * 锁死, 而主人 2026-10-19 的口径是**平板与展开的折叠屏保留横屏**。另外 Android 16 起, targetSdk 36+
 * 的应用在大屏 (最短边 ≥ 600 dp) 上系统会**忽略应用声明的方向** —— 两份口径同时存在时, 代码这一份
 * 才是说得清的那一份
 *
 * 判据只有一个: 设备当前折叠状态下窗口的最短边。600 dp 是 Android 自己 `sw600dp` 那一刀, 也是
 * "手机 / 平板"这一刀; 折叠屏合上是手机档 (锁竖屏), 展开跨过 600 dp 就交给系统
 *
 * 宽度未知 (0 或负数) 按手机档处理: 真机上读不到宽度的唯一情形是配置还没落下, 那时先钉竖屏,
 * 等配置到了下一次判定自己会更正
 */
internal object PortraitPolicy {

    /** 大屏的门槛, 与 Android 的 `sw600dp` 是同一刀 */
    const val TABLET_SMALLEST_WIDTH_DP = 600

    /** 这一页该请求哪一档方向 */
    enum class Orientation {
        /** 手机: 钉成竖屏, 转屏不动 */
        PORTRAIT,

        /** 平板与展开的折叠屏: 不声明方向, 跟随系统 (传感器 / 用户锁定的那一档) */
        FOLLOW_SYSTEM,
    }

    fun orientationFor(smallestWidthDp: Int): Orientation =
        if (smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP) Orientation.FOLLOW_SYSTEM else Orientation.PORTRAIT
}
