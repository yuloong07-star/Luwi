package io.github.yuloong07star.luwi.sample.whalewidget

import android.content.Context

/**
 * 桌面上能切到的一套动作
 *
 * 两种来源各有一条画法, 差别只在"帧从哪来":
 *
 * - **随包那一套** (`bundled > 0`): 帧是 `res/drawable-nodpi/whale_frame_<n>_<i>.png`, 按资源 id 画
 * - **主人导进来的** (`imported != null`): 帧是这个 app 私有目录里的 png, 走内容 URI 交给宿主
 *   (见 [WhaleImports] 与 [WhaleFramesProvider])
 *
 * [title] 是**取好的字符串**: 随包那一套从 strings.xml 取, 导入那一份是主人自己起的名字
 */
internal data class WhaleDance(
    /** 稳定的键: 随包那一套是 `dance-1`, 导入的是那份导入的目录名 */
    val key: String,
    val title: String,
    /** > 0 表示随包那一套是第几套 (这一版只有 1); 导入的是 0 */
    val bundled: Int,
    /** 一共几张 */
    val frames: Int,
    /** 一张停多少毫秒 */
    val frameMs: Int,
    /** 导入那一份自己 (随包的是 null) */
    val imported: WhaleImports.Import?,
)

/**
 * 现在能切到的那几套 = 随包那一套 + 主人导进来的那些
 *
 * 这个表**每次都现算**: 导入与删除都是主人在伴侣界面里当场做的, 缓存一份就会变成"刚导进来那一套
 * 要重启应用才看得见"
 *
 * **为什么随包只有一套**: 主人 2026-10-10 定的口径 —— 那两段新动作不用我生成, **用户自己导**。
 * 随包这一份是主人自己给的那只参考动图, 其余的入口在伴侣界面那颗「导入一份 GIF」
 */
internal object WhaleDances {

    /** 随包那一套的键 */
    val bundledKeys = listOf("dance-1")

    private val bundledTitles = intArrayOf(R.string.dance_1)

    /** 一整套: 随包的在前面, 导入的按目录名排在后面 */
    fun catalog(context: Context): List<WhaleDance> {
        val bundled = bundledKeys.mapIndexed { index, key ->
            WhaleDance(
                key = key,
                title = context.getString(bundledTitles[index]),
                bundled = index + 1,
                frames = WhaleFrames.COUNT,
                frameMs = WhaleWidgetProvider.FLIP_MS,
                imported = null,
            )
        }
        val imported = WhaleImports.all(context).map { item ->
            WhaleDance(
                key = item.key,
                title = item.title,
                bundled = 0,
                frames = item.frames,
                frameMs = item.frameMs,
                imported = item,
            )
        }
        return bundled + imported
    }

    /** 按键找; 找不到就回第一套 (键在"导入被删掉"之后会失效, 那时落到第一套比什么都不画好) */
    fun find(context: Context, key: String): WhaleDance {
        val all = catalog(context)
        return all.firstOrNull { it.key == key } ?: all.first()
    }

    /** 按序号取 (可负, 绕回表里), 给"换下一套"用 */
    fun at(context: Context, index: Int): WhaleDance {
        val all = catalog(context)
        return all[index.mod(all.size)]
    }

    /** 工具传进来的那个参数: 认键, 也认从 1 起的序号; 认不出来回 -1 */
    fun indexOf(context: Context, token: String): Int {
        val all = catalog(context)
        val wanted = token.trim().lowercase()
        all.forEachIndexed { index, dance -> if (dance.key.lowercase() == wanted) return index }
        val number = wanted.toIntOrNull() ?: return -1
        return (number - 1).mod(all.size)
    }
}
