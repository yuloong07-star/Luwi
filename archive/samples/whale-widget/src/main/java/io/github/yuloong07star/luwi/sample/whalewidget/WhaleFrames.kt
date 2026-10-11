package io.github.yuloong07star.luwi.sample.whalewidget

import android.content.Context

/**
 * 逐帧那一份的资源名
 *
 * 帧图是构建时由 `art/` 下那三份 GIF 抽出来的 (见仓库里那份生成脚本的说明), 名字是
 * `whale_frame_<第几套>_<第几帧>`. 一张一帧地写进 `ViewFlipper` 需要**按序号取资源 id**, 而帧数会
 * 随美术那份动图变, 所以这里按名字查一次并缓存 —— 手写一张 96 个 id 的常量表反而不如它经得起改
 *
 * 查不到时回 0 (`RemoteViews.setImageViewResource` 收到 0 会把那一格画空), 于是"资源少了一张"
 * 在桌面上是少一帧, 不是崩
 */
internal object WhaleFrames {

    /** 每一套抽多少帧 (与生成脚本里那个数一致) */
    const val COUNT = 32

    private val cache = HashMap<String, Int>()

    /** 第 [dance] 套 (从 0 起) 的第 [frame] 帧 */
    fun art(context: Context, dance: Int, frame: Int): Int {
        val name = "whale_frame_%d_%02d".format(dance + 1, frame)
        return cache.getOrPut(name) {
            context.resources.getIdentifier(name, "drawable", context.packageName)
        }
    }
}
