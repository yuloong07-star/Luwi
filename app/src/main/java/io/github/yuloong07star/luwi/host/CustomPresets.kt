package io.github.yuloong07star.luwi.host

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 把随包发的预设声明落成设备上的事实 —— **新装的手机开箱就该有「自定义模式」与「探索模式」**
 *
 * **2026-10-18 主人改了形状**: 手机模式与视频模式那两条独立的预设下线 ("因为已经在自定义模式里了"),
 * 于是这一条链现在只补探索那一份, 并且顺手把老机器里那两段声明删掉 ([LEGACY_ROWS])
 *
 * 主人 2026-10-09 报的那条: "安装手机时默认的 custom 预设声明没有装进去, 导致进行语音输入时开不了
 * 新的会话"。根子是这一条链**只有开发机上手工做过**: `dsh-custom-mode` 那个插件（它把
 * `$DSH_HOME/.agent-presets/custom/` 注册成 dsh 的 `custom` 预设）从来没进过 APK, 而本应用的
 * 「手机模式 / 视频模式」正是写进那个助手的 `prompt.md` 的。
 *
 * 现在两处调用, 都是幂等的:
 *
 * | 什么时候 | 做什么 |
 * | :-- | :-- |
 * | [DshHostService.onCreate] (与 `LwSeed` 并排) | 耐久副本 + `.agent-presets/custom/` 那五个文件 |
 * | [DshHost.spawn] (`PluginOverlay.write` 之前) | profile 的初始化 / 合并 + 三份声明进 patch + 那条链接 |
 *
 * **一律"缺什么补什么"**: 已存在的 `prompt.md`（那是主人自己的人设, [LwModes] 还会往里写模式正文）、
 * 已有的 `selectedDefault`、已经有的 patch 行, 一个都不动。判据里那几段纯算术抽成了纯函数, 见
 * `CustomPresetsTest`
 *
 * 那三步装法与 2026-10-05 在模拟器上手工装通的那一次**逐字一致** (见
 * `D:\apk\docs\DSH-LW-2.0.0-可行性参考.md` 3.1.2): 包放两处、profile 锚点那条软链、`dependencies`
 * 与 `dsh.profile.bundles` 各加一行
 */
internal object CustomPresets {

    private const val TAG = "CustomPresets"

    /** 内置的那个插件包名, 与 `tools/pack-host.mjs` 拷进树里的那个目录同名 */
    const val PACKAGE = "dsh-custom-mode"

    /** 那个助手的目录名: 与 [io.github.yuloong07star.luwi.channel.LwModes] 说的 `custom` 是同一个 */
    const val ASSISTANT = "custom"

    /** `selectedDefault` 指向它: 新会话默认落在自定义模式上 (切模式的人设就是写进它里面那份 `prompt.md`) */
    const val DEFAULT_PRESET = "custom"

    /**
     * 那个助手要的那五个文件, **顺序就是写的顺序** (与上游 `seed.mjs` 的 `PRESET_FILES` 一致)
     *
     * 前三个是用户数据 (`prompt.md` / `preset.yml` / `agent.cordis.yml`), 只补不缺; 后两个是**代码**
     * 模块, 上游插件激活时会把它们刷新到与包内一致 —— 这里只负责让它们先在
     */
    val PRESET_FILES = listOf(
        "agent.cordis.yml",
        "preset.yml",
        "prompt.md",
        "prompt-reader.mjs",
        "prompt-tool.mjs",
    )

    /** 声明各自那个 loader 行 id: 幂等判据就是"patch 里有没有这一行" */
    private const val ROW_EXPLORE = "preset-explore"
    private const val ROW_REGISTRY = "agent-preset-registry"

    /**
     * 随包发的模式预设: 行 id 与随 host 树那份 `lw-presets/<名字>.patch.yml` 的对照表
     *
     * **一张表三处读** ([ensurePatch] 按它补声明, [describe] 按它报缺哪一份, [LEGACY_ROWS] 是同一张
     * 名单的反面): 2.7.8 批次 7 把写死在 `ensurePatch` 里的那张表提了出来, 于是加一份 / 退一份都只改这里
     */
    val PRESET_ROWS: List<Pair<String, String>> = listOf(
        ROW_EXPLORE to "explore",
    )

    /**
     * **已经下线的那两条**: 手机模式 (`preset-mobile-use`) 与视频模式 (`preset-video`)
     *
     * 主人 2026-10-18: "删除手机模式和视频模式, 因为已经在自定义模式里了" —— 那两套提示词本来就装在
     * `assets/modes/` 下那几份 md 里, 切模式时由应用写进自定义模式的 `prompt.md`, 单独的预设只是同一个
     * 东西的第二份。**这里要把它们从老机器的 profile patch 里真正删掉** (只停止随包发的话, 已经装过的
     * 机器里那两段声明还在, 选择器里就永远看得见), 见 [withoutRow]
     */
    val LEGACY_ROWS: List<String> = listOf("preset-mobile-use", "preset-video")

    /** `link:` 那一条: 相对 profile 目录 (`profiles/web`) 算, 落到的就是耐久副本 [durablePackage] */
    private const val LINK = "link:../../../plugins/$PACKAGE"

    /**
     * dsh 自己那份 `initProfile` 写下的 profile patch 模板
     *
     * **逐字抄自 `@deepseek-ai/dsh-app-boot`** (`PROFILE_PATCH_TEMPLATE` 与 `PROFILE_PNPM_WORKSPACE`):
     * 只有 profile 还不存在时才用它, 而 dsh 的 `initProfile` 对已存在的文件一个都不碰 —— 所以这里
     * 写的形状必须与它认的那一份一样, 否则新机上会出现"两份都以为自己初始化过、谁都没配对"
     */
    private val PATCH_TEMPLATE = listOf(
        "# Your patch layer for this dsh profile, applied after every bundle layer:",
        "# a top-level YAML array of loader patch entries (id-targeted config",
        "# overrides, disables, and insert lists; `!!js` expressions allowed).",
        "[]",
        "",
    ).joinToString("\n")

    private val WORKSPACE_TEMPLATE = listOf(
        "packages:",
        "  - .",
        "",
        "nodeLinker: hoisted",
        "autoInstallPeers: false",
        "",
    ).joinToString("\n")

    /** 我们自己那一条注册表行: 只在 patch 里**没有** `agent-preset-registry` 时才追加 */
    private val REGISTRY_ROW = listOf(
        "- id: $ROW_REGISTRY",
        "  name: \"@deepseek-ai/dsh-agent-preset-registry\"",
        "  config:",
        "    default: standard",
        "    selectedDefault: $DEFAULT_PRESET",
        "",
    ).joinToString("\n")

    /** 写 JSON 那一份: 与 dsh 自己 `JSON.stringify(manifest, null, 2)` 同一个形状 */
    private val writer = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private val reader = Json { ignoreUnknownKeys = true }

    /* ── 两处调用 ────────────────────────────────────────────────────────── */

    /**
     * 耐久副本与那个助手的五个文件 (宿主服务起来时叫一次, 与 `LwSeed.ensure` 并排)
     *
     * 落在 `$DSH_HOME` 那一侧, 所以**不碰 profile**; 每一步失败都只记日志 —— 缺一个包不该拦住 host
     * 起来, 而缺哪一样在 `lw_mode status` 的 `customPreset` 那一行上看得见
     */
    fun ensure(context: Context) {
        val source = hostPackage(context)
        if (!source.isDirectory) {
            Log.w(TAG, "the host tree carries no $PACKAGE at ${source.absolutePath}, so the custom preset cannot be installed")
            return
        }
        runCatching { copyDurable(context, source) }
            .onFailure { Log.w(TAG, "the durable copy of $PACKAGE could not be written", it) }
        runCatching { seedAssistant(context, source) }
            .onFailure { Log.w(TAG, "the custom assistant files could not be seeded", it) }
    }

    /**
     * profile 那一半 (host spawn 之前叫, 排在 `PluginOverlay.write` 前面)
     *
     * 四件事: 那份 `package.json` 加我们的包、三段声明进 patch、`profiles/node_modules` 下那条链接、
     * 以及 profile 还不存在时按 dsh 的 web 模板把它建出来 —— 顺序不分先后, 但**都在 spawn 之前做完**,
     * 这样第一次启动的 host 就把三份预设一起读进来, 不需要重启
     */
    fun ensureProfile(context: Context) {
        val source = hostPackage(context)
        if (!source.isDirectory) {
            Log.w(TAG, "the host tree carries no $PACKAGE, so the profile cannot be pointed at it")
            return
        }
        val profile = profileDirectory(context)
        runCatching { profile.mkdirs() }
        runCatching { ensureManifest(profile) }
            .onFailure { Log.w(TAG, "the profile manifest could not be updated", it) }
        runCatching { ensureWorkspace(profile) }
            .onFailure { Log.w(TAG, "the pnpm workspace file could not be written", it) }
        runCatching { ensurePatch(context, profile) }
            .onFailure { Log.w(TAG, "the preset declarations could not be appended", it) }
        runCatching { ensureLink(context, source) }
            .onFailure { Log.w(TAG, "the profile link to $PACKAGE could not be made", it) }
    }

    /** 给设置页之外那几处读数用: 一件一行地说"这一份齐了没有" */
    fun describe(context: Context): String {
        val source = hostPackage(context)
        val assistant = assistantDirectory(context)
        val missing = PRESET_FILES.filter { !File(assistant, it).isFile }
        val profile = profileDirectory(context)
        val patch = File(profile, "cordis.patch.yml")
            .takeIf { it.isFile }?.readText().orEmpty()
        val rows = (PRESET_ROWS.map { it.first } + ROW_REGISTRY).filter { !hasRow(patch, it) }
        val bundled = File(profile, "package.json").takeIf { it.isFile }?.readText()
            ?.contains("\"$PACKAGE\"") == true
        return buildString {
            append("package: ")
            append(if (source.isDirectory) "in the host tree" else "MISSING")
            append(if (bundled) ", registered as a bundle" else ", NOT registered as a bundle")
            append(", assistant files: ")
            append(if (missing.isEmpty()) "all ${PRESET_FILES.size}" else "missing ${missing.joinToString(", ")}")
            append(", profile rows: ")
            append(if (rows.isEmpty()) "all in place" else "missing ${rows.joinToString(", ")}")
        }
    }

    /* ── 落位那几步 ──────────────────────────────────────────────────────── */

    private fun hostPackage(context: Context): File = File(File(DshHost.hostRoot(context), "node_modules"), PACKAGE)

    private fun durablePackage(context: Context): File = File(File(context.filesDir, "plugins"), PACKAGE)

    private fun home(context: Context): File = File(context.filesDir, DshHost.HOME_DIR)

    private fun profilesRoot(context: Context): File = File(home(context), "profiles")

    private fun profileDirectory(context: Context): File = File(profilesRoot(context), "web")

    private fun assistantDirectory(context: Context): File = File(home(context), ".agent-presets/$ASSISTANT")

    /**
     * 耐久副本: `<host 树>/node_modules/dsh-custom-mode` → `files/plugins/dsh-custom-mode`
     *
     * host 树换个 APK 版本会被整个重解压, 所以树外要留一份 ([LINK] 指的也是这一份)。版本不同就整份
     * 重拷 (包很小, 而"半新半旧"最难查)
     */
    private fun copyDurable(context: Context, source: File) {
        val target = durablePackage(context)
        if (target.isDirectory && versionOf(target) == versionOf(source)) return
        runCatching { target.deleteRecursively() }
        target.parentFile?.mkdirs()
        copyTree(source, target)
        Log.i(TAG, "the durable copy of $PACKAGE is now ${versionOf(target)} at ${target.absolutePath}")
    }

    /** 那个助手要的五个文件: 缺什么补什么, 已有的一律不动 */
    private fun seedAssistant(context: Context, source: File) {
        val preset = File(source, "preset")
        if (!preset.isDirectory) {
            Log.w(TAG, "$PACKAGE carries no preset/ directory, so the assistant cannot be seeded")
            return
        }
        val target = assistantDirectory(context)
        target.mkdirs()
        var written = 0
        PRESET_FILES.forEach { name ->
            val to = File(target, name)
            // **人有自己那份就不动**: prompt.md 是主人自己的正文 (也可能是 LwModes 刚写进去的模式正文)
            if (to.isFile && to.length() > 0) return@forEach
            val from = File(preset, name)
            if (!from.isFile) {
                Log.w(TAG, "$PACKAGE carries no preset/$name")
                return@forEach
            }
            runCatching { to.outputStream().use { out -> from.inputStream().use { it.copyTo(out) } } }
                .onSuccess { written += 1 }
                .onFailure { Log.w(TAG, "preset/$name could not be seeded", it) }
        }
        if (written > 0) Log.i(TAG, "$written of ${PRESET_FILES.size} preset file(s) seeded into ${target.absolutePath}")
    }

    /** profile 那份 `package.json`: 加我们的依赖与 bundle, 别的键一个不动 */
    private fun ensureManifest(profile: File) {
        val file = File(profile, "package.json")
        val existing = file.takeIf { it.isFile }?.readText()
        val next = withProfileManifest(existing)
        if (next == existing) return
        // 改写前留一份 —— 那是主人自己那份 profile 的登记表
        if (existing != null) {
            val backup = File(profile, "package.json.bak-before-$PACKAGE")
            if (!backup.isFile) runCatching { backup.writeText(existing) }
        }
        file.writeText(next)
        Log.i(TAG, "the profile manifest now carries $PACKAGE")
    }

    /** 随包发的那份声明按行 id 补, 下线的那几条按行 id 删 (已有的一律不动) */
    private fun ensurePatch(context: Context, profile: File) {
        val file = File(profile, "cordis.patch.yml")
        // profile 还不存在时从 dsh 自己那份模板起 (见 [PATCH_TEMPLATE])
        var patch = if (file.isFile) file.readText() else PATCH_TEMPLATE
        val before = patch
        // 模式预设: 正文随 host 树走 (lw-presets/), 我们只取开头那段 `- insert:`
        for ((row, staged) in PRESET_ROWS) {
            if (hasRow(patch, row)) continue
            val source = File(File(DshHost.hostRoot(context), "lw-presets"), "$staged.patch.yml")
            val block = source.takeIf { it.isFile }?.readText()?.let(::insertBlock)
            if (block == null) {
                Log.w(TAG, "the staged declaration ${source.absolutePath} is missing or has no `- insert:` block")
                continue
            }
            patch = appendBlock(patch, block)
        }
        // **下线那两条真的从老机器的 patch 里删掉** (见 [LEGACY_ROWS]): 只停发的话, 已经装过的机器里
        // 那两段声明还在, 预设选择器里就一直看得见它们
        for (row in LEGACY_ROWS) {
            if (!hasRow(patch, row)) continue
            patch = withoutRow(patch, row)
            Log.i(TAG, "the retired preset declaration $row is now out of ${file.absolutePath}")
        }
        // 我们那一条注册表行: 只负责"没有时写一份默认", 已经有的话一个字都不动 (主人可能自己选过)
        if (!hasRow(patch, ROW_REGISTRY)) patch = appendBlock(patch, REGISTRY_ROW)
        if (patch != before) {
            file.parentFile?.mkdirs()
            file.writeText(patch)
            Log.i(TAG, "the preset declarations are in ${file.absolutePath}")
        }
    }

    /**
     * profile 还缺那份 pnpm-workspace 时补上 (dsh 的 `initProfile` 对已存在的文件一个都不碰)
     *
     * 那份文件是"out-of-tree 的插件怎么被 pnpm 认"的设置, 而设备上不会真的跑 pnpm —— 补它只是让
     * profile 的形状与 dsh 自己初始化出来的一模一样
     */
    private fun ensureWorkspace(profile: File) {
        val file = File(profile, "pnpm-workspace.yaml")
        if (file.isFile) return
        file.writeText(WORKSPACE_TEMPLATE)
    }

    /**
     * `profiles/node_modules/dsh-custom-mode` → host 树里那一份
     *
     * 手工装那一次记下的一条: **loader 解析 bundle 目录走"安装锚点 + profile 锚点"两条, 而 import
     * 那一行是相对 profile 的** —— 光把包放进 host 树, 设备上的表现就是 `failed to import`。所以这条
     * 链接是必需的; 建不出符号链接的文件系统 (FUSE 之类) 上退回拷一份
     */
    private fun ensureLink(context: Context, source: File) {
        val nodes = File(profilesRoot(context), "node_modules")
        nodes.mkdirs()
        val link = File(nodes, PACKAGE)
        if (link.exists() && File(link, "package.json").isFile) return
        // 断链也要能删掉: `File.exists()` 跟着链接走, 所以这里用不跟随的那一个 API
        runCatching { Files.deleteIfExists(link.toPath()) }
        runCatching { link.deleteRecursively() }
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), source.toPath()) }.isSuccess
        if (linked) {
            Log.i(TAG, "the profile link ${link.absolutePath} points at ${source.absolutePath}")
            return
        }
        copyTree(source, link)
        Log.i(TAG, "symlinks are not available here, so $PACKAGE was copied to ${link.absolutePath}")
    }

    /* ── 纯函数: 那几段算术都在这儿, 判据在 CustomPresetsTest ───────────── */

    /**
     * 一份 patch 里开头那段 `- insert:` (到下一个顶层 `- ` 之前)
     *
     * `presets/mobile-use/cordis.patch.yml` 末尾还带着它自己的 `agent-preset-registry` 与
     * `session-log-deepseek` 两行, 而我们要的只是声明那一段 —— 认法就是"顶层那两个字符是 `- `":
     * 段里的子行都缩进过
     */
    fun insertBlock(source: String): String? {
        val lines = source.lines()
        val start = lines.indexOfFirst { it.startsWith("- insert:") }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("- ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n").trimEnd() + "\n"
    }

    /** 这一行在不在: 判据是"某个顶层行的 id 就是它" (缩进过的子行不算) */
    fun hasRow(patch: String, rowId: String): Boolean =
        patch.lines().any { it.trimStart() == "- id: $rowId" }

    /**
     * 把一个行 id 所在的**那一条顶层条目**从 patch 里删掉 (见 [LEGACY_ROWS])
     *
     * 认法与 [insertBlock] 是同一套: 一条顶层条目从 `- ` 那一行起, 到下一条顶层 `- ` 之前; 段里只要有
     * 一行 `- id: <rowId>` (顶格或缩进过) 就整条丢掉。注释与空行跟着**后一条**走, 所以删掉中间那一条
     * 不会把下一段的注释一起吃掉; 收尾把连续空行压成一个
     *
     * 幂等: 已经删过的 patch 再跑一遍一个字都不变 (`ensurePatch` 每次 spawn 都会调它)
     */
    fun withoutRow(patch: String, rowId: String): String {
        val lines = patch.lines()
        val kept = mutableListOf<String>()
        var index = 0
        // 文件头那几行注释不属于任何条目: 先原样留着
        while (index < lines.size && !lines[index].startsWith("- ")) {
            kept += lines[index]
            index += 1
        }
        while (index < lines.size) {
            val start = index
            var end = index + 1
            while (end < lines.size && !lines[end].startsWith("- ")) end += 1
            val entry = lines.subList(start, end)
            if (entry.none { it.trimStart() == "- id: $rowId" }) kept += entry
            index = end
        }
        val collapsed = mutableListOf<String>()
        for (line in kept) {
            if (line.isBlank() && (collapsed.isEmpty() || collapsed.last().isBlank())) continue
            collapsed += line
        }
        while (collapsed.isNotEmpty() && collapsed.last().isBlank()) collapsed.removeAt(collapsed.lastIndex)
        return collapsed.joinToString("\n") + "\n"
    }

    /**
     * 把一段声明追加进 patch
     *
     * 空数组那一份 (`[]`) 是**替换**而不是追加: `- insert:` 写在 `[]` 后面不是合法 YAML 数组
     */
    fun appendBlock(patch: String, block: String): String {
        val lines = patch.lines().toMutableList()
        val content = lines.count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val empty = lines.indexOfFirst { it.trim() == "[]" }
        if (empty >= 0 && content == 1) {
            lines[empty] = block.trimEnd()
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        val base = patch.trimEnd()
        return (if (base.isEmpty()) block.trimEnd() else "$base\n\n${block.trimEnd()}") + "\n"
    }

    /**
     * profile 那份 `package.json` 该长什么样
     *
     * [existing] 为空 (profile 还不存在) 时就按 dsh 的 web 模板起一份 —— 名字、`private`、两个出厂
     * bundle 都与 `dsh-app-boot` 那一份一致, 只是多了我们的包。已有的话只加两处:
     * `dependencies.dsh-custom-mode` 与 `dsh.profile.bundles` 里的那一名
     */
    fun withProfileManifest(existing: String?): String {
        val root = if (existing.isNullOrBlank()) {
            freshProfileTemplate()
        } else {
            reader.parseToJsonElement(existing).jsonObject
        }
        // 依赖与 bundle 两张表都按"已有的原样留着, 我们的追加在后面"来合 (键已经在时替换在原来的位置)
        val dependencies = linkedMapOf<String, JsonElement>()
        (root["dependencies"] as? JsonObject)?.forEach { (key, value) -> dependencies[key] = value }
        dependencies[PACKAGE] = JsonPrimitive(LINK)
        val dsh = root["dsh"] as? JsonObject
        val profile = dsh?.get("profile") as? JsonObject
        val bundles = (profile?.get("bundles") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toMutableList()
            ?: mutableListOf()
        if (PACKAGE !in bundles) bundles += PACKAGE
        val profileNext = linkedMapOf<String, JsonElement>()
        profile?.forEach { (key, value) -> if (key != "bundles") profileNext[key] = value }
        profileNext["bundles"] = JsonArray(bundles.map { JsonPrimitive(it) })
        val dshNext = linkedMapOf<String, JsonElement>()
        dsh?.forEach { (key, value) -> if (key != "profile") dshNext[key] = value }
        dshNext["profile"] = JsonObject(profileNext)
        val merged = linkedMapOf<String, JsonElement>()
        root.forEach { (key, value) ->
            if (key != "dependencies" && key != "dsh") merged[key] = value
        }
        merged["dependencies"] = JsonObject(dependencies)
        merged["dsh"] = JsonObject(dshNext)
        return writer.encodeToString(JsonObject.serializer(), JsonObject(merged)) + "\n"
    }

    /** dsh 给 `web` 这个 profile 的出厂模板 (见 `@deepseek-ai/dsh-app-boot` 的 `PROFILE_TEMPLATES`) */
    private fun freshProfileTemplate(): JsonObject = buildJsonObject {
        put("name", "dsh-profile-web")
        put("private", true)
        put("dependencies", buildJsonObject { })
        put(
            "dsh",
            buildJsonObject {
                put(
                    "profile",
                    buildJsonObject {
                        put(
                            "bundles",
                            buildJsonArray {
                                add(JsonPrimitive("@deepseek-ai/dsh-base"))
                                add(JsonPrimitive("@deepseek-ai/dsh-web-app"))
                            },
                        )
                    },
                )
            },
        )
    }

    /** profile 与 patch 那两个模板 (只在缺文件时写) */
    fun patchTemplate(): String = PATCH_TEMPLATE

    fun workspaceTemplate(): String = WORKSPACE_TEMPLATE

    /** 一个目录底下那份 `package.json` 的 version, 读不出来就是空串 */
    private fun versionOf(directory: File): String = runCatching {
        reader.parseToJsonElement(File(directory, "package.json").readText()).jsonObject["version"]
            ?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    /** 递归拷一份目录 (不跟随符号链接, 与 `cpSync` 那条路同一个形状) */
    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child -> copyTree(child, File(target, child.name)) }
            return
        }
        target.parentFile?.mkdirs()
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }
}
