package io.github.yuloong07star.luwi.plugin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import io.github.yuloong07star.luwi.MainActivity
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.plugin.PluginPaths
import io.github.yuloong07star.luwi.plugin.PluginStore
import java.io.File

/**
 * 宿主槽的内容组装 (方案 3.1): `onUpdate` → 拼一份 `RemoteViews` 交出去
 *
 * 几条在伴侣那一份上已经踩通、原样搬过来的事:
 *
 * - 帧图**不走 binder**, 走 `content://` + `setImageViewUri` (几十张位图过 binder 会撞 1 MB 上限)
 * - `ViewFlipper` + `autoStart` 是真会动的 (`AnimatedImageDrawable` 与 `<animation-list>` 都不会)
 * - 帧数不受预置布局限制: 组装时用 `addView` 往 flipper 里逐帧加子 `ImageView`
 * - **每一趟组装前都重放一次** `grantUriPermission` —— 那条授权是内存里的, 桌面重画一次就没了
 *
 * 空态也是 `RemoteViews`: 桌面那一格在人把插件卸掉之后仍然被摆在那儿, 它自己得说清楚为什么是空的
 */
internal object WidgetHost {

    private const val TAG = "LwWidgetHost"

    const val ACTION_NEXT = "io.github.yuloong07star.luwi.widget.NEXT"
    const val ACTION_TOOL = "io.github.yuloong07star.luwi.widget.TOOL"
    const val EXTRA_ID = "id"
    const val EXTRA_TOOL = "tool"
    const val EXTRA_ARGS = "args"

    /** 一屏一套: 现在是哪一套存在插件自己那一格里 (点一下换下一套只改它) */
    private fun setFile(context: Context, id: String): File =
        File(PluginStore.dataDir(context, id), "widget-set")

    fun currentSet(context: Context, id: String): String? =
        setFile(context, id).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    private fun rememberSet(context: Context, id: String, key: String) {
        setFile(context, id).writeText("$key\n")
    }

    /** 点一下换下一套; 只有一套时就停在原地 */
    fun nextSet(context: Context, id: String?): String? {
        if (id == null) return null
        val spec = specOf(context, id) ?: return null
        val sets = spec.animationSets()
        if (sets.size <= 1) return sets.firstOrNull()?.key
        val here = sets.indexOfFirst { it.key == currentSet(context, id) }
        val chosen = sets[((if (here < 0) 0 else here) + 1) % sets.size]
        rememberSet(context, id, chosen.key)
        return chosen.key
    }

    /** 一次组装: 谁挂在这个槽上、它现在长什么样 */
    fun views(context: Context, slot: String): RemoteViews {
        val holder = WidgetSlots.holder(context, slot)
        val manifest = holder?.let { PluginStore.manifest(context, it) }
        val directory = holder?.let { PluginStore.packageDir(context, it) }
        val spec = manifest?.widget?.takeIf { it.slot == slot }
        if (holder == null) {
            return empty(context, slot, context.getString(R.string.settings_widget_slot_vacant))
        }
        if (manifest == null || directory == null || spec == null) {
            Log.i(TAG, "slot $slot: $holder 的插件不在 (读不出清单), 画空态")
            return empty(context, slot, context.getString(R.string.settings_widget_slot_gone))
        }
        val pushed = WidgetPush.read(context, holder)
        return when (slot) {
            WidgetSlot.ANIM -> anim(context, holder, spec, pushed, directory)
            WidgetSlot.IMAGE -> image(context, holder, spec, pushed, directory)
            else -> empty(context, slot, context.getString(R.string.settings_widget_slot_vacant))
        }
    }

    /** 重画一个槽现在挂着的每一格 */
    fun refresh(context: Context, slot: String) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, receiverOf(slot)))
        ids.forEach { manager.updateAppWidget(it, views(context, slot)) }
    }

    /** 全部槽重画一遍 (装 / 卸 / 开机 / 插件推内容之后都走它) */
    fun refreshAll(context: Context) {
        WidgetSlot.ALL.forEach { slot -> runCatching { refresh(context, slot) } }
    }

    /** 这个插件现在挂着哪几个槽 (卸载时把它清掉再重画) */
    fun refreshHolding(context: Context, id: String) {
        WidgetSlot.ALL.forEach { slot ->
            if (WidgetSlots.holder(context, slot) == id) runCatching { refresh(context, slot) }
        }
    }

    fun receiverOf(slot: String): Class<*> = when (slot) {
        WidgetSlot.ANIM -> WidgetSlotAnim::class.java
        else -> WidgetSlotImage::class.java
    }

    private fun specOf(context: Context, id: String): WidgetSpec? =
        PluginStore.manifest(context, id)?.widget

    /**
     * 动画槽: `ViewFlipper` 一张一帧
     *
     * 逐帧查一遍文件在不在 —— 装机时查过, 但插件目录主人能手工改, 所以这里**再兜一次**
     */
    private fun anim(
        context: Context,
        id: String,
        spec: WidgetSpec,
        pushed: WidgetPush.Content?,
        directory: File,
    ): RemoteViews {
        val sets = spec.animationSets()
        val chosen = sets.firstOrNull { it.key == currentSet(context, id) } ?: sets.first()
        val frames = chosen.frameNames().mapNotNull { name ->
            runCatching { PluginPaths.resolve(directory, "${chosen.dir}/$name", "帧图") }.getOrNull()
        }.filter { it.isFile }
        val view = RemoteViews(context.packageName, R.layout.lw_widget_slot_anim)
        view.removeAllViews(R.id.lw_slot_flipper)
        if (frames.isEmpty()) {
            Log.w(TAG, "slot anim: $id 的帧图一张都读不到 (${chosen.dir})")
            return empty(context, WidgetSlot.ANIM, context.getString(R.string.settings_widget_slot_frames))
        }
        val uris = frames.mapIndexed { index, _ -> WidgetFramesProvider.uri(id, chosen.key, index) }
        grantToHomes(context, uris)
        frames.forEachIndexed { index, _ ->
            view.addView(
                R.id.lw_slot_flipper,
                RemoteViews(context.packageName, R.layout.lw_widget_slot_frame).apply {
                    setImageViewUri(R.id.lw_slot_frame_image, uris[index])
                },
            )
        }
        view.setInt(R.id.lw_slot_flipper, "setFlipInterval", chosen.intervalMs)
        val label = pushed?.label?.takeIf { it.isNotBlank() }
            ?: listOf(spec.name, chosen.key.takeIf { sets.size > 1 }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        view.setTextViewText(R.id.lw_slot_label, label)
        view.setOnClickPendingIntent(R.id.lw_slot_root, tapIntent(context, id, spec.tap))
        return view
    }

    private fun image(
        context: Context,
        id: String,
        spec: WidgetSpec,
        pushed: WidgetPush.Content?,
        directory: File,
    ): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.lw_widget_slot_image)
        val title = pushed?.title?.takeIf { it.isNotBlank() } ?: spec.title
        val text = pushed?.text?.takeIf { it.isNotBlank() } ?: spec.text
        val imagePath = pushed?.image?.takeIf { it.isNotBlank() } ?: spec.image
        val buttons = pushed?.buttons?.takeIf { it.isNotEmpty() }?.map {
            WidgetSpec.Button(it.label, it.action, it.tool)
        } ?: spec.buttons

        if (imagePath.isNotBlank()) {
            val file = runCatching { PluginPaths.resolve(directory, imagePath, "widget.json 的 image") }.getOrNull()
            if (file != null && file.isFile) {
                val uri = WidgetFramesProvider.imageUri(id)
                grantToHomes(context, listOf(uri))
                view.setImageViewUri(R.id.lw_slot_image_pic, uri)
                view.setViewVisibility(R.id.lw_slot_image_pic, android.view.View.VISIBLE)
            } else {
                view.setViewVisibility(R.id.lw_slot_image_pic, android.view.View.GONE)
            }
        } else {
            view.setViewVisibility(R.id.lw_slot_image_pic, android.view.View.GONE)
        }
        view.setTextViewText(R.id.lw_slot_image_title, title.ifBlank { spec.name })
        view.setTextViewText(R.id.lw_slot_image_text, text)
        view.setViewVisibility(R.id.lw_slot_image_text, if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
        bindButton(context, view, id, R.id.lw_slot_image_btn1, buttons.getOrNull(0), 0)
        bindButton(context, view, id, R.id.lw_slot_image_btn2, buttons.getOrNull(1), 1)
        view.setOnClickPendingIntent(R.id.lw_slot_root, openIntent(context))
        return view
    }

    private fun bindButton(
        context: Context,
        view: RemoteViews,
        id: String,
        viewId: Int,
        button: WidgetSpec.Button?,
        index: Int,
    ) {
        if (button == null || button.label.isBlank()) {
            view.setViewVisibility(viewId, android.view.View.GONE)
            return
        }
        view.setViewVisibility(viewId, android.view.View.VISIBLE)
        view.setTextViewText(viewId, button.label)
        val intent = when (button.action) {
            "open" -> openIntent(context)
            "tool" -> toolIntent(context, id, button.tool, index)
            else -> null
        }
        if (intent == null) view.setOnClickPendingIntent(viewId, null) else view.setOnClickPendingIntent(viewId, intent)
    }

    /** 一个槽空了: 哪一格都不画, 只留一句说得清的正文 */
    private fun empty(context: Context, slot: String, message: String): RemoteViews {
        return if (slot == WidgetSlot.ANIM) {
            RemoteViews(context.packageName, R.layout.lw_widget_slot_anim).apply {
                removeAllViews(R.id.lw_slot_flipper)
                setTextViewText(R.id.lw_slot_label, message)
                setOnClickPendingIntent(R.id.lw_slot_root, openIntent(context))
            }
        } else {
            RemoteViews(context.packageName, R.layout.lw_widget_slot_image).apply {
                setViewVisibility(R.id.lw_slot_image_pic, android.view.View.GONE)
                setTextViewText(R.id.lw_slot_image_title, message)
                setTextViewText(R.id.lw_slot_image_text, "")
                setViewVisibility(R.id.lw_slot_image_text, android.view.View.GONE)
                setViewVisibility(R.id.lw_slot_image_btn1, android.view.View.GONE)
                setViewVisibility(R.id.lw_slot_image_btn2, android.view.View.GONE)
                setOnClickPendingIntent(R.id.lw_slot_root, openIntent(context))
            }
        }
    }

    private fun tapIntent(context: Context, id: String, tap: String): PendingIntent? = when (tap) {
        "next" -> PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, receiverOf(WidgetSlot.ANIM)).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        "open" -> openIntent(context)
        else -> null
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toolIntent(context: Context, id: String, tool: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            10 + slot,
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(ACTION_TOOL)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TOOL, tool),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 把这一趟要用的 `content://` 读权限放给"现在正在当桌面"的那几个包
     *
     * 放出去的是一条**内存里的**授权, 桌面重新 inflate 一遍就可能没了; 所以每一趟组装都重放一次
     * (幂等且便宜), 开机 / 解锁 / 覆盖安装那几处另有 [WidgetRegrant] 再补一轮
     *
     * 找"谁是桌面"这一步需要清单里那段 `<queries>` (`MAIN` + `HOME`): targetSdk 33 起不声明
     * 就看不见别的包, 而那是一种静默失败
     */
    private fun grantToHomes(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val homes = try {
            @Suppress("DEPRECATION")
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
        } catch (problem: Throwable) {
            emptyList()
        }
        Log.i(TAG, "grant read on ${uris.size} uri(s) to ${homes.ifEmpty { listOf("<none>") }}")
        homes.forEach { home ->
            uris.forEach { uri ->
                runCatching { context.grantUriPermission(home, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }
}
