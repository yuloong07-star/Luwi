package io.github.yuloong07star.luwi.sample.whalewidget

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 伴侣自带的那一页 (协议 10.3) —— 能切到的那几套都在这儿预览与切换, 点「导入一份 GIF」还能加新的
 *
 * 预览那一格渲染的是**真的 RemoteViews** (`WhaleWidgetProvider.preview(...).apply(...)`), 所以
 * "这一页里动不动"就是"桌面上动不动", 不用两处各验一遍
 *
 * 导入走系统那一份文件选择器 (`ACTION_OPEN_DOCUMENT`): 选中的那一份**抄进来**, 原来的文件一个字节
 * 都不动 —— 于是"删掉"删的只是这一份副本
 */
class WhaleScreen : Activity() {

    private lateinit var rows: LinearLayout
    private lateinit var note: TextView
    private var message: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    /** 导入回来 (或从别处切回来) 都要重画: 那一份新图这一页上还没见过 */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun build(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        column.addView(label(getString(R.string.screen_title), 20f, true))
        column.addView(label(getString(R.string.screen_body), 14f, false))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)
        column.addView(
            Button(this).apply {
                text = getString(R.string.import_gif)
                setOnClickListener { pick() }
            },
        )
        column.addView(
            Button(this).apply {
                text = getString(R.string.refresh_widget)
                setOnClickListener { WhaleWidgetProvider.refresh(this@WhaleScreen) }
            },
        )
        note = label("", 13f, false)
        column.addView(note)
        column.addView(label(getString(R.string.add_hint), 13f, false))
        return ScrollView(this).apply { addView(column) }
    }

    /** 把"现在有哪几套、正在用哪一套"整块重画 —— 列表短, 重建比逐项对齐简单 */
    private fun render() {
        val all = WhaleDances.catalog(this)
        val key = WhaleStore.current(this)
        rows.removeAllViews()
        all.forEach { dance -> rows.addView(row(dance, dance.key == key)) }
        note.text = message.ifEmpty { getString(R.string.import_note, WhaleImports.root(this).absolutePath) }
    }

    private fun row(dance: WhaleDance, current: Boolean): View {
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(89))
        }
        holder.addView(WhaleWidgetProvider.preview(this, dance.key).apply(this, holder))

        val title = label(
            if (current) getString(R.string.current_mark, dance.title) else dance.title,
            15f,
            current,
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = getString(if (current) R.string.in_use else R.string.use_this)
                setOnClickListener {
                    if (!current) WhaleWidgetProvider.select(this@WhaleScreen, dance.key)
                    render()
                }
            },
        )
        if (dance.imported != null) {
            actions.addView(
                Button(this).apply {
                    text = getString(R.string.forget)
                    setOnClickListener {
                        val gone = WhaleImports.remove(this@WhaleScreen, dance.key)
                        message = getString(
                            if (gone) R.string.forget_done else R.string.forget_failed,
                            dance.title,
                        )
                        // 正在用那一套被删掉时落回第一套, 不然桌面上那一只会停在"查不到"那一支
                        if (WhaleStore.current(this@WhaleScreen) == dance.key) {
                            WhaleWidgetProvider.select(this@WhaleScreen, WhaleDances.bundledKeys.first())
                        } else {
                            WhaleWidgetProvider.refresh(this@WhaleScreen)
                        }
                        render()
                    }
                },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(holder)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actions)
        }
    }

    /** 选一份图: 类型放开成"所有图片" (有的选择器不认 `image/gif` 那个精确的类型), 是不是 GIF 由解的时候说了算 */
    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val source = data?.data
        if (resultCode != RESULT_OK || source == null) return
        val application = applicationContext
        val title = displayName(source)
        // 解一份 GIF 要几十毫秒到几秒, 这一页不该跟着卡住
        Thread {
            val outcome = runCatching { WhaleImports.add(application, source, title) }
            runOnUiThread {
                message = outcome.fold(
                    onSuccess = { item ->
                        WhaleWidgetProvider.select(this, item.key)
                        getString(R.string.import_done, item.title, item.frames)
                    },
                    onFailure = { problem ->
                        getString(R.string.import_failed, problem.message ?: problem.javaClass.simpleName)
                    },
                )
                render()
            }
        }.start()
    }

    /** 选择器给的那个名字 (没有就用文件名), 去掉 `.gif` 那一段当标题 */
    private fun displayName(uri: Uri): String {
        val asked = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val name = asked ?: uri.lastPathSegment.orEmpty()
        val plain = File(name).nameWithoutExtension
        return plain.ifEmpty { "whale" }.take(24)
    }

    private fun label(text: String, size: Float, title: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(null, if (title) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(if (title) 0 else 6), 0, dp(if (title) 8 else 6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PICK = 0x57A1
    }
}
