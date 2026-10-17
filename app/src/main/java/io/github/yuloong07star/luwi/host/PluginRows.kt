package io.github.yuloong07star.luwi.host

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 那几行"装进树里, 但要能被 dsh 的设置面板改"的插件 —— 它们住 **profile 自己的 patch**
 *
 * 2026-10-16 主人报的那条: "生图保存失败, 面板上那几项都改不了"。能把它压死的有**两件**事, 各自都
 * 足够让面板回同一句错, 两件都得对:
 *
 * **一、挂错了层** —— patch 层序是 `bundle → profile 自己的 cordis.patch.yml → $DSH_HOME/cordis.patch.yml
 * → 命令行 --patch` (`dsh-app-boot/src/profile-context.ts` 的 `readProfilePatches`), 而
 * `applyEntryPatches` 只让**后面的** patch 命中**前面 insert 出来的行**
 * (`cordis-plugin-include/src/index.ts`)。面板那条写入由 config-editor 追加在 profile patch 末尾,
 * 于是它永远命中不了命令行覆盖层最后插进来的那一行 —— 一度以为这就是全部病根, 把这一行从
 * [PluginOverlay] 挪进 profile patch (下面那一段), 仍然是同一句错
 *
 * **二、盘上写的形态与 Loader 眼里的形态对不上** (2026-10-16 事后在设备上用真代码复现出来的)
 *
 * - dsh 读 patch 时会把 insert 子行里**绝对路径**的 name 锚成 `file://` URL
 *   (`dsh-app-boot` 的 `anchorInsertedPluginNames`: `isAbsolute(name)` 就 `pathToFileURL(resolve(...))`),
 *   于是 Loader 里这一行的 `options.name` 是 `file:///data/...`, 盘中那份文本里却是裸路径
 * - 面板存一次配置时, config-editor 把 `config` 行追加进盘上这份 patch, 那一行的 `name` 抄的是
 *   Loader 那个 (锚过的) 值 (`packages/boot/config-editor/src/index.ts` 的 `document.add`); 而它落盘
 *   前的自检做的是裸 `yaml.load` (**不 anchoring**), 同一条 insert 子行在自检里还是裸路径 ——
 *   名字对不上, `applyEntryPatches` 把这条 config 行按 `patch: name mismatch for "imagegen"` 丢掉,
 *   自检于是看到"生效值还是继承来的那一份", 抛出
 *   `Configuration for "imagegen" is overridden by a home patch or command-line overlay` ——
 *   明明没有覆盖层也报"被覆盖层覆盖", 就是这么来的
 * - 所以盘上这一行**直接写成 `file://` 形态** ([fileUrlOf]): 那不是新造一个东西, 它逐字就是 anchoring
 *   之后喂给 Loader 的那一份, 两侧一致之后面板追加的 config 行命中得上。旧安装里已经写下的裸路径由
 *   [namedRow] 在每启一次 host 时改成这个形态 (只改 `name:` 那一行)
 *
 * 修好之后 [PluginOverlay] 那一份仍然管另外几件事: 只按 id 覆盖已有行 (官方语音 bundle 那两条) 与插入
 * "设置面板不需要改它"的那几行
 *
 * 三条纪律: **[OWNED] 里的每一行都必须能在树里找到文件** (树没带它就不许留一行指向不存在的文件,
 * 那会让 loader 报一条 entry 载入失败); **用户写给它的 `config:` 一个字节都不动**; **`name:` 必须是
 * [fileUrlOf] 那个形态** —— 面板能不能存进去全看这一条 (判据在 `tools/check-image-edit.mjs`,
 * `tools/check-overlay-yaml.mjs` 与 `PluginRowsTest`)
 */
internal object PluginRows {

    private const val TAG = "PluginRows"

    /** profile 那份 patch 的文件名 (与 dsh 的 `PROFILE_PATCH_FILENAME` 一致) */
    private const val PATCH_FILE = "cordis.patch.yml"

    private const val PROFILE = "web"

    /**
     * 我们放进 profile patch 的那几行, **顺序就是写的顺序**
     *
     * `imagegen` (第三方生图插件 `@dickpy/dsh-imagegen`) 与 `mneme` (`@modusensus/dsh-mneme` 记忆)
     * 都是"装进树里, 但要能被 dsh 的设置面板改"的那一类: 它们的渠道、密钥、模型与数据目录是**用户
     * 在界面上配**的东西, 而装在命令行覆盖层上会让整个设置面板变成只读
     *
     * `config` 是**随包写进 insert 子行**的那几项 (面板之后追加的顶层 config 行压在它上面, 于是主人
     * 改过的值照样生效)。只有 mneme 要: 它的记忆库缺省落 `~/.dsh/memory`, 而本机 `HOME` 指向**工作区**
     * (`DshHost.spawn` 那两行), 不钉住就会在工作区根下长出一个 `.dsh/memory` —— 这与生图插件当年
     * 在 `/sdcard/DSH/.dsh/dsh-imagegen` 里那一坨是同一类毛病
     */
    private class Owned(
        val id: String,
        val entry: String,
        val config: (Context) -> List<String> = { emptyList() },
    )

    private val OWNED = listOf(
        Owned("imagegen", "node_modules/@dickpy/dsh-imagegen/lib/index.js"),
        Owned(
            "mneme",
            "node_modules/@modusensus/dsh-mneme/lib/index.js",
            config = ::memoryConfig,
        ),
    )

    /** 记忆库落在 `$DSH_HOME/memory`, 不落在工作区的 `~/.dsh/memory` (见 [Owned]) */
    private fun memoryConfig(context: Context): List<String> =
        listOf("memoryDir: '${File(File(context.filesDir, DshHost.HOME_DIR), "memory").absolutePath}'")


    /**
     * 每次 host 起来之前对一次账 (排在 [PluginOverlay.write] 前面, 与 `CustomPresets` 那两步并排)
     *
     * 四件事都在纯函数里: 文件在而这一行不在就补, 文件在而这一行还是裸路径就改成 [fileUrlOf] 形态,
     * 文件不在而这一行在就摘, 别的行一个都不碰
     */
    fun ensure(context: Context) {
        runCatching { reconcile(context) }
            .onFailure { Log.w(TAG, "the plugin rows could not be reconciled", it) }
    }

    private fun reconcile(context: Context) {
        val root = DshHost.hostRoot(context)
        val file = patchFile(context)
        // profile 还不存在时按 dsh 自己那份模板起 (与 CustomPresets 用的是同一份, 见 patchTemplate)
        var patch = if (file.isFile) file.readText() else CustomPresets.patchTemplate()
        val before = patch
        for (row in OWNED) {
            val target = File(root, row.entry)
            val config = row.config(context)
            patch = when {
                target.isFile -> ensuredRow(patch, row.id, target.absolutePath, config)
                hasInsertRow(patch, row.id) -> withoutRow(patch, row.id).also {
                    Log.w(TAG, "the host tree carries no ${row.entry}, so the ${row.id} row was removed")
                }
                else -> {
                    Log.w(TAG, "the host tree carries no ${row.entry}, so ${row.id} cannot be mounted")
                    patch
                }
            }
        }
        if (patch == before) return
        file.parentFile?.mkdirs()
        file.writeText(patch)
        Log.i(TAG, "the plugin rows are in ${file.absolutePath}")
    }

    private fun patchFile(context: Context): File =
        File(File(File(File(context.filesDir, DshHost.HOME_DIR), "profiles"), PROFILE), PATCH_FILE)

    /* ── 纯函数: 判据在 PluginRowsTest ─────────────────────────────────── */

    /**
     * 把这一行对到"树里有这个文件"的状态: 没有就补一段 insert, 有但 `name:` 还是裸路径 (旧版本写下的)
     * 就只把那一行改成 [fileUrlOf] 形态
     *
     * 幂等: 已经是那个形态时一个字节都不动 (每启一次 host 都会叫一遍)
     */
    fun ensuredRow(patch: String, rowId: String, entryPath: String, config: List<String> = emptyList()): String {
        val name = fileUrlOf(entryPath)
        return if (hasInsertRow(patch, rowId)) namedRow(patch, rowId, name) else withRow(patch, rowId, name, config)
    }

    /**
     * 文件路径 -> Loader 认的那个 URL 形态 (`file:///data/...`), 与 Node 的 `pathToFileURL(路径).href`
     * 逐字一致 (dsh 的 anchoring 走的就是它)
     *
     * 这一棵树上那几个字符一个都不会出现 (整条路径都是 `filesDir/host/...`, 而包名是固定的), 照抄这份
     * 转义表是为了"哪天树挪到别处、包名换成带别的字符的"也不至于悄悄对不上
     */
    fun fileUrlOf(path: String): String = buildString {
        append("file://")
        for (character in path) append(
            when (character) {
                '%' -> "%25"
                '\\' -> "%5C"
                ' ' -> "%20"
                '#' -> "%23"
                '?' -> "%3F"
                '\n' -> "%0A"
                '\r' -> "%0D"
                '\t' -> "%09"
                else -> character.toString()
            },
        )
    }

    /**
     * "有没有任何一行提到这个 id" (缩进过的 insert 子行与顶层的 config 行都算) —— 与
     * [CustomPresets.hasRow] 同一个认法
     */
    fun hasRow(patch: String, rowId: String): Boolean =
        patch.lines().any { it.trimStart() == "- id: $rowId" }

    /**
     * "我们这一行 (那一行 `- insert:` 里的子行) 在不在"
     *
     * **判"挂没挂"只能用这一个**: 设置面板存过一次之后, 同一个 id 会多出一行**顶层**的 config 行,
     * 而"要不要挂"这件事只由 insert 那一行说了算 —— 拿"有没有任何一行提到这个 id"去判, 会在
     * "只剩 config 行"时以为已经挂上了, 于是插件从树上悄悄消失
     */
    fun hasInsertRow(patch: String, rowId: String): Boolean =
        patch.lines().any { indentOf(it) > 0 && it.trimStart() == "- id: $rowId" }

    /** 一段 YAML: 一行 `- insert:` 加一行 id 加一行 name (name 加引号, 免得 `@` 之类的字符绊住解析),
     * 再按需跟上这几项 `config:` (本应用代写的缺省值, 面板之后追加的那一行压在它上面) */
    fun rowBlock(rowId: String, name: String, config: List<String> = emptyList()): String = buildList {
        addAll(listOf("- insert:", "    - id: $rowId", "      name: '$name'"))
        if (config.isNotEmpty()) {
            add("      config:")
            config.forEach { add("        $it") }
        }
        add("")
    }.joinToString("\n")

    /**
     * 把我们那一行放进 patch
     *
     * **放在开头那段注释之后、第一个内容行之前** —— 设置面板追加的 config 行永远在文件末尾, 于是
     * "insert 在前、config 在后"这条顺序不会因为补过一次行就跟丢 (那一行要是落在 config 行后面,
     * 面板里存过的值会静默失效, 再点保存又回到"被覆盖"那句错)
     *
     * `name` 要的是 [fileUrlOf] 那个形态, 不是裸路径 —— 见文件头第二条
     */
    fun withRow(patch: String, rowId: String, name: String, config: List<String> = emptyList()): String {
        // 幂等: 已经挂上了就一个字节都不动 (每启一次 host 都会叫一遍)
        if (hasInsertRow(patch, rowId)) return patch
        val block = rowBlock(rowId, name, config).trimEnd()
        val lines = patch.lines().toMutableList()
        val empty = lines.indexOfFirst { it.trim() == "[]" }
        val content = lines.count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (empty >= 0 && content == 1) {
            lines[empty] = block
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        val head = lines.indexOfFirst { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
        val at = if (head < 0) lines.size else head
        lines.addAll(at, block.lines())
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /**
     * 把这一行 (那一行 `- insert:` 里的子行) 的 `name:` 改成 [name], 别的行一个字节都不动
     *
     * 这一步管的是"旧安装里已经写着裸路径"那一档: 那种盘面上面板永远存不进去 (见文件头第二条), 而
     * 我们每启一次 host 就在这里把它对成 [fileUrlOf] 形态。**只有 `- insert:` 段里那一行会被碰**:
     * 往下第一个缩进更深的 `name:` 才是它的入口; 顶层的 config 行是主人存在设置面板里的东西
     *
     * 已经是这个形态时原样返回 (幂等)
     */
    fun namedRow(patch: String, rowId: String, name: String): String {
        val lines = patch.lines().toMutableList()
        val at = lines.indexOfFirst { indentOf(it) > 0 && it.trimStart() == "- id: $rowId" }
        if (at < 0) return patch
        val indent = indentOf(lines[at])
        val entry = (at + 1 until lines.size).firstOrNull { index ->
            lines[index].isNotBlank() && indentOf(lines[index]) > indent &&
                lines[index].trimStart().startsWith("name:")
        } ?: return patch
        val line = "${" ".repeat(indentOf(lines[entry]))}name: '$name'"
        if (lines[entry] == line) return patch
        lines[entry] = line
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /**
     * 把我们那一行摘掉 (树里没有这个文件时必须摘)
     *
     * **只摘 `- insert:` 段里那一行 (缩进过的那个)**: 顶层的 config 行是主人存在设置面板里的东西,
     * 一个字节都不动 —— 树把包带回来的那一天它自己就重新生效
     *
     * 从那一行往下, 缩进更深的都算它的 (`name:` 与可能的 `config:`); 段里没有别的 `- id:` 时才把
     * `- insert:` 外壳一起收掉; 整份 patch 只剩注释就还原成 `[]`
     */
    fun withoutRow(patch: String, rowId: String): String {
        val lines = patch.lines().toMutableList()
        val at = lines.indexOfFirst { indentOf(it) > 0 && it.trimStart() == "- id: $rowId" }
        if (at < 0) return patch
        val indent = indentOf(lines[at])
        var end = at + 1
        while (end < lines.size && (lines[end].isBlank() || indentOf(lines[end]) > indent)) end += 1
        // 往上找管着它那个 `- insert:` (缩进更浅的那一个)
        val shell = (at - 1 downTo 0).firstOrNull { indentOf(lines[it]) < indent && lines[it].trimStart() == "- insert:" } ?: -1
        // **这个壳的地盘到下一个缩进不比它深的行为止** (2026-10-16): 整份文件里往上找兄弟是错的 ——
        // 别人那个 `- insert:` 段里的子行会被当成它的兄弟, 于是自己这个只剩空壳的 `- insert:` 留了下来
        val shellEnd = if (shell < 0) {
            shell
        } else {
            var scan = shell + 1
            while (scan < lines.size && (lines[scan].isBlank() || indentOf(lines[scan]) > indentOf(lines[shell]))) scan += 1
            scan
        }
        // 兄弟 = 这个壳里除了我们这一行之外, 缩进更深的那几个 `- id:` (它自己那一段的兄弟都在 [at, end) 之外)
        val siblings = shell >= 0 && (shell + 1 until shellEnd).any { index ->
            (index < at || index >= end) && indentOf(lines[index]) > indentOf(lines[shell]) &&
                lines[index].trimStart().startsWith("- id: ")
        }
        lines.subList(at, end).clear()
        if (shell >= 0 && !siblings) lines.removeAt(shell)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        val content = lines.count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        return (if (content == 0) lines + "[]" else lines).joinToString("\n").trimEnd() + "\n"
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length
}
