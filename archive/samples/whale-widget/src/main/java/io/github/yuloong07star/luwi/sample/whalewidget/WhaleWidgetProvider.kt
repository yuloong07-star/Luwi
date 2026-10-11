package io.github.yuloong07star.luwi.sample.whalewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * 桌面上那一只鲸鱼娘 (协议 10.4)
 *
 * **动的是宿主那一侧, 我们不常驻任何进程**: 交出去的是一台 `ViewFlipper` (`android:autoStart`),
 * 里面一张一帧; 启动器 inflate 完一挂上去, `onAttachedToWindow` 里就 `startFlipping()`, 之后由它
 * 自己按那一套自己的间隔翻。桌面小组件最怕的"后台被冻住动画就停"这条在这里不存在
 *
 * 两套动作两种画法: 随包那三套按资源 id 画, 主人导进来的那一份按内容 URI 画 (见 [WhaleImports])
 *
 * 点一下 = 换下一套 ([ACTION_NEXT]); 伴侣界面与模型那三条工具都走同一份 [WhaleStore]
 */
class WhaleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT) {
            next(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {

        const val ACTION_NEXT = "io.github.yuloong07star.luwi.sample.whalewidget.NEXT"

        /** 随包那三套一帧停多少毫秒 (32 张一圈 3.2 秒) */
        const val FLIP_MS = 100

        /** 界面 / 工具 / 点一下三条路改完账都调它: 桌面上每一只都当场重画 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WhaleWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        /** 换下一套并重画桌面, 回换成了哪一套 (点一下与 `whale_next` 都走它) */
        internal fun next(context: Context): WhaleDance {
            val all = WhaleDances.catalog(context)
            val here = all.indexOfFirst { it.key == WhaleStore.current(context) }
            val chosen = all[((if (here < 0) 0 else here) + 1).mod(all.size)]
            WhaleStore.setCurrent(context, chosen.key)
            refresh(context)
            return chosen
        }

        /** 换成点名的那一套; 认不出来回 null, 由调用方去说到底怎么了 */
        internal fun select(context: Context, key: String): WhaleDance? {
            val all = WhaleDances.catalog(context)
            val chosen = all.firstOrNull { it.key == key } ?: return null
            WhaleStore.setCurrent(context, chosen.key)
            refresh(context)
            return chosen
        }

        /**
         * 伴侣界面拿它当预览: 那一页里渲染的就是**真的 RemoteViews**, 与桌面上的是同一份东西 ——
         * "预览里动不动"就是"桌面上动不动", 不用两处各验一遍
         */
        fun preview(context: Context, key: String): RemoteViews {
            val dance = WhaleDances.find(context, key)
            return RemoteViews(context.packageName, R.layout.whale_widget).apply {
                removeAllViews(R.id.whale_flipper)
                repeat(dance.frames) { frame ->
                    addView(R.id.whale_flipper, frameView(context, dance, frame))
                }
                // 一套自己的节奏: 导入那一份跟着它原来的帧时长走, 随包那三套是固定的那一档
                setInt(R.id.whale_flipper, "setFlipInterval", dance.frameMs)
                setTextViewText(R.id.whale_name, dance.title)
            }
        }

        private fun views(context: Context): RemoteViews =
            preview(context, WhaleStore.current(context)).apply {
                // 导入那一份的帧走内容 URI, 而 provider 是 exported=false 的: **读权限得我们自己放出去**,
                // 宿主不会凭空拿到它 (2026-10-10 实测: 不放就是
                // `Permission Denial ... from com.google.android.apps.nexuslauncher`, 桌面那一格直接
                // 变成「Can't load widget」)
                grantFramesToHosts(context, WhaleStore.dance(context))
                // 只有桌面上那一只吃这一条: 点一下换下一套
                setOnClickPendingIntent(R.id.whale_root, nextIntent(context))
            }

        /**
         * 把这一套的逐帧图读权限放给"现在正在当桌面"的那几个应用
         *
         * 认的是 `CATEGORY_HOME` 那一份 (与 `lw_apps` 同一个取法), 而不是某个写死的包名: 谁的桌面、
         * 装几个桌面, 在这一台机器上说了算的都是它。随包那一套走资源 id, 用不着这一条
         */
        private fun grantFramesToHosts(context: Context, dance: WhaleDance) {
            val imported = dance.imported ?: return
            val homes = try {
                @Suppress("DEPRECATION")
                context.packageManager
                    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                    .map { it.activityInfo.packageName }
            } catch (problem: Throwable) {
                emptyList()
            }
            // 一行读数: 放给了谁、放了几条 —— 这一条链最会静默失败的一步就是"一个桌面包都没看见"
            android.util.Log.i(
                "WhaleWidget",
                "grant read on ${dance.frames} frames of ${imported.key} to ${homes.ifEmpty { listOf("<none>") }}",
            )
            homes.forEach { home ->
                repeat(dance.frames) { frame ->
                    context.grantUriPermission(
                        home,
                        WhaleFramesProvider.uri(imported.key, frame),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }

        /** 一套里的一张: 随包那一套按资源 id 画, 导入那一份按内容 URI 画 */
        private fun frameView(context: Context, dance: WhaleDance, frame: Int): RemoteViews {
            val imported = dance.imported
            return if (imported != null) {
                uriFrameView(context, WhaleFramesProvider.uri(imported.key, frame))
            } else {
                resourceFrameView(context, WhaleFrames.art(context, dance.bundled - 1, frame))
            }
        }

        private fun resourceFrameView(context: Context, art: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.whale_frame).apply {
                setImageViewResource(R.id.whale_frame_image, art)
            }

        /**
         * 导入那一份: 交出去的是**内容 URI**, 图由宿主自己去解
         *
         * 位图走 binder 会撞上 1 MB 的上限 (32 张就连小图也超), 而 URI 只过一个短字符串; 读那一个
         * URI 的权限由小组件那一侧的框架自己放给宿主 (见 [WhaleFramesProvider])
         */
        private fun uriFrameView(context: Context, uri: Uri): RemoteViews =
            RemoteViews(context.packageName, R.layout.whale_frame).apply {
                setImageViewUri(R.id.whale_frame_image, uri)
            }

        private fun nextIntent(context: Context): PendingIntent {
            val intent = Intent(context, WhaleWidgetProvider::class.java).setAction(ACTION_NEXT)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
