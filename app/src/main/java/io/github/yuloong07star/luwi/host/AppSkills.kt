package io.github.yuloong07star.luwi.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「装了哪个应用, 就装哪个技能」
 *
 * 随包带一份**应用 → 技能**的目录 (`assets/app-skills/catalog.json`, 源在仓库的 `app-skills/`),
 * 这一份做三件事: 读目录 / 看这台机器上装了哪些应用 / 把命中那些技能抄进 `$DSH_HOME/skills/`
 *
 * 三条写在这里的规矩:
 *
 * - **只加不改**: 目标技能已经在了就一个字节都不动 (与 [LwSeed] 同一条) —— 主人改过的技能不该
 *   被"一键"覆盖掉
 * - **"装了哪些"问的是启动器看得见的那一份** (`MAIN` + `LAUNCHER`), 与 `lw_apps` 同一个口径:
 *   系统组件不算"主人装的应用"
 * - 抄进去的是**技能正文**, 不是插件: 它落在 `$DSH_HOME/skills/<名字>/SKILL.md`, dsh 下一轮就看得见
 */
internal object AppSkills {

    private const val TAG = "AppSkills"

    /**
     * **技能的来源仓库** (主人 2026-10-10: "skill 应该检测 https://github.com/yuloong07-star/app-skills 这个")
     *
     * 它有两件事: 那份"应用 → 技能"的目录在根下 `index.json`, 正文在 `skills/<id>/SKILL.md`
     */
    const val REPO = "yuloong07-star/app-skills"

    /** 取远端文件走 GitHub 的 contents API: 这台机器上 `api.github.com` 通而 `raw.githubusercontent.com` 不通 */
    private const val CONTENTS = "https://api.github.com/repos/$REPO/contents/"

    /** 取回来那份 `index.json` 原样存的名字 (落在 `$DSH_HOME/` 下, 与技能目录挨着) */
    private const val CACHE = "app-skills-index.json"

    /** 一次取文件的超时: 设置页那颗按钮不能把人晾在那儿 */
    private const val TIMEOUT_MS = 8_000

    /** assets 里那份目录 */
    private const val CATALOG = "app-skills/catalog.json"

    /** 每个技能正文放在自己那个目录下, 名字与 dsh 认的一致 */
    private const val SKILL_FILE = "SKILL.md"

    /** dsh 的技能目录, 与 [LwSeed] 同一个落点 */
    private const val SKILLS = "skills"

    /** 目录里的一条: 一个技能, 与它会命中的那几个包名 */
    data class Entry(val skill: String, val title: String, val apps: List<String>)

    /** 一条技能在这台机器上的现状 */
    data class Match(
        val skill: String,
        val title: String,
        /** 目录里列的那几个包名里, 这台机器上真装了的 */
        val hits: List<String>,
        /** 技能正文是不是已经在 `$DSH_HOME/skills/` 里了 */
        val present: Boolean,
    )

    /** 一次扫描或一次安装的结论 */
    data class Report(val matches: List<Match>, val wrote: List<String>, val failed: List<String>, val error: String)

    /**
     * 技能目录: **先看从 [REPO] 取回来的那一份缓存, 没有再退回随包那份**
     *
     * 远端那一份是主人点「刷新技能目录」时取回来的 (见 [refresh]); 平时只读本地, 所以这个函数
     * **一个网络请求都不发** —— 它是在设置页的组合里同步跑的那一条 (联网会把那一段卡住)
     */
    fun catalog(context: Context): List<Entry> {
        val cached = File(File(context.filesDir, DshHost.HOME_DIR), CACHE)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        if (cached != null) {
            val remote = runCatching { remoteEntries(cached) }.getOrNull().orEmpty()
            if (remote.isNotEmpty()) return remote
        }
        return bundled(context)
    }

    /**
     * 远端那份 `index.json` → 目录条目 (**纯函数**, 见 AppSkillsTest)
     *
     * 字段对应: `id` → 技能名 (也就是 `skills/<id>/SKILL.md` 那个目录名), `name` → 显示名,
     * `package` → 命中它才算"这台机器上有这个应用" (那一份是一个包一个条目, 所以这里就一项)
     */
    internal fun remoteEntries(text: String): List<Entry> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val apps = root["apps"] as? JsonArray ?: return emptyList()
        return apps.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val packageName = item["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty() || packageName.isEmpty()) return@mapNotNull null
            val title = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { id }
            Entry(id, title, listOf(packageName))
        }
    }

    /**
     * **去 [REPO] 把目录取回来并缓存** —— 设置页那颗「刷新技能目录」走它
     *
     * @return (条数, 一句人话): 取不到、仓库改了、缓存写不进去都如实说 —— 这条链上"静默什么都没发生"
     *   是最难查的那种
     */
    fun refresh(context: Context): Pair<Int, String> {
        val text = fetch("index.json")
            ?: return 0 to "没取到 $REPO 的 index.json (网络不通, 或者仓库改了)"
        val entries = remoteEntries(text)
        if (entries.isEmpty()) return 0 to "取回来的 index.json 里一条应用都没有"
        val written = runCatching {
            val file = File(File(context.filesDir, DshHost.HOME_DIR), CACHE)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.isSuccess
        return entries.size to if (written) {
            "目录已更新: ${entries.size} 条应用 (来源 $REPO)"
        } else {
            "取到了 ${entries.size} 条, 但缓存没写进去"
        }
    }

    /** 取一份远端文件的字节; null = 没取到 (状态码不是 200 / 网络问题), 由调用方决定怎么兜 */
    private fun fetchBytes(path: String): ByteArray? = runCatching {
        val connection = (URL(CONTENTS + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // 要的是**原文**: 不带这个头拿回来的是带 base64 的 JSON 壳
            setRequestProperty("Accept", "application/vnd.github.raw")
            // GitHub 的 API 不带 User-Agent 会 403
            setRequestProperty("User-Agent", "Luwi")
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { it.readBytes() }
        } finally {
            runCatching { connection.disconnect() }
        }
    }.getOrNull()

    /** 取一份远端文件当文本 (技能正文都是文本; 真取到二进制也按 UTF-8 解, 写回去的还是原字节) */
    private fun fetch(path: String): String? = fetchBytes(path)?.toString(Charsets.UTF_8)

    /** 随包那份清单一: 远端没缓存 (或取不到) 时用它兜底 */
    private fun bundled(context: Context): List<Entry> {
        val text = try {
            context.assets.open(CATALOG).bufferedReader().use { it.readText() }
        } catch (problem: Throwable) {
            Log.w(TAG, "no $CATALOG in this package", problem)
            return emptyList()
        }
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (problem: Throwable) {
            Log.w(TAG, "$CATALOG is not a JSON object", problem)
            return emptyList()
        }
        val entries = root["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val skill = item["skill"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val apps = (item["apps"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (skill.isEmpty() || apps.isEmpty()) return@mapNotNull null
            Entry(skill, item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { skill }, apps)
        }
    }

    /**
     * 这台机器上"看得见"的应用
     *
     * 用启动器那一份而不是 `pm list packages`: 后者会把系统组件与后台服务也算进来, 而"主人装了
     * 什么"这件事的判据一直是"桌面上有没有它的图标" (与 `lw_apps` 同一个取法)
     */
    private fun launcherPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (problem: Throwable) {
            Log.w(TAG, "could not ask the package manager for launcher apps", problem)
            emptySet()
        }
    }

    /** 技能目录里现在有哪些技能 (`$DSH_HOME/skills/` 下每一个带 SKILL.md 的目录) */
    fun present(context: Context): Set<String> {
        val directory = File(File(context.filesDir, DshHost.HOME_DIR), SKILLS)
        val found = directory.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, SKILL_FILE).isFile }
            .map { it.name }
            .toSet()
        return found
    }

    /** 扫一遍: 目录里有哪几条在这台机器上命中, 各自装了没有 */
    fun scan(context: Context): Report {
        val apps = launcherPackages(context)
        val have = present(context)
        val matches = catalog(context).map { entry ->
            Match(entry.skill, entry.title, entry.apps.filter { it in apps }, entry.skill in have)
        }
        return Report(matches.filter { it.hits.isNotEmpty() }, emptyList(), emptyList(), "")
    }

    /**
     * 一键: 把技能抄进 `$DSH_HOME/skills/`
     *
     * @param only 点名装这几个技能 (**不要求**那个应用在这台机器上 —— 点名就是点名, 给桥上那条
     *   `op=install` 用); 空表示"按已安装的应用来", 那才是设置页那颗按钮走的路
     */
    fun install(context: Context, only: List<String> = emptyList()): Report {
        val apps = launcherPackages(context)
        val have = present(context)
        val entries = catalog(context).filter { entry ->
            if (only.isEmpty()) entry.apps.any { it in apps } else entry.skill in only
        }
        val target = File(File(context.filesDir, DshHost.HOME_DIR), SKILLS)
        val wrote = mutableListOf<String>()
        val failed = mutableListOf<String>()
        entries.forEach { entry ->
            // 已经在的不动: "一键"不覆盖主人改过的技能
            if (entry.skill in have) return@forEach
            val destination = File(File(target, entry.skill), SKILL_FILE)
            // **正文先问 [REPO] 要** (主人 2026-10-10: 技能以 app-skills 那个仓库为准): 取不到才退回
            // 随包里那一份 —— 远端更新了正文, 这台机器下一次"装入"就该拿到新的
            val body = fetch("skills/${entry.skill}/$SKILL_FILE") ?: packagedBody(context, entry.skill)
                ?: run {
                    Log.w(TAG, "the body of ${entry.skill} is neither on $REPO nor in this package")
                    failed += entry.skill
                    return@forEach
                }
            val copied = try {
                destination.parentFile?.mkdirs()
                destination.writeText(body)
                true
            } catch (problem: Throwable) {
                Log.w(TAG, "could not install the ${entry.skill} skill", problem)
                false
            }
            if (copied) wrote += entry.skill else failed += entry.skill
        }
        // 重新看一遍账, 好让调用方拿到"现在到底装了哪些"
        val after = scan(context)
        return Report(after.matches, wrote, failed, "")
    }

    /** 那一个技能正文还在不在包里 (点名装一个不存在的名字时要能说清) */
    private fun packaged(context: Context, skill: String): Boolean =
        packagedBody(context, skill) != null || fetch("skills/$skill/$SKILL_FILE") != null

    /** 随包那份正文 (远端取不到时的兜底); null = 包里也没有这一份 */
    private fun packagedBody(context: Context, skill: String): String? = runCatching {
        context.assets.open("app-skills/$skill/$SKILL_FILE").bufferedReader().use { it.readText() }
    }.getOrNull()

    /**
     * **删掉点名的那几个技能** (主人 2026-10-10: "设置里的 skill 板块加上删除 skill 按钮")
     *
     * 装是主人点的那一下, 删也必须是主人点的那一下 —— 与"只加不改"配成一对。设置页那一段里点一条
     * 已经在的技能先问一句"删不删", 那个对话框本身就是确认 (与「快捷指令」那一段同一个做法)
     *
     * 三条护栏:
     *
     * - **名字只许是目录名**: 带 `/` `\` 或者就是 `.` / `..` 的当场拒 —— 这是这条链上唯一不可逆的
     *   动作, 不该由一个拼出来的名字决定删哪儿
     * - **只删技能目录**: 名字下面必须有 `SKILL.md` (与 [present] 同一个判据), 少了就一个字节不动
     * - **只删这一份**: `deleteRecursively()` 只作用在 `$DSH_HOME/skills/<名字>/` 上
     */
    fun remove(context: Context, only: List<String>): Report {
        val target = File(File(context.filesDir, DshHost.HOME_DIR), SKILLS)
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        only.filter { it.isSafeSkillName() }.forEach { name ->
            val directory = File(target, name)
            val gone = directory.isDirectory && File(directory, SKILL_FILE).isFile &&
                runCatching { directory.deleteRecursively() }.getOrDefault(false)
            if (gone) removed += name else failed += name
        }
        // 不安全的名字也记成"没删掉", 免得回执里少一条而看不出来
        failed += only.filterNot { it.isSafeSkillName() }
        // 重新看一遍账, 让调用方拿到"现在到底还剩哪些"
        return Report(scan(context).matches, removed, failed, "")
    }

    /** 这个名字能不能当"技能目录名"用 (纯函数, 见 AppSkillsTest) */
    internal fun String.isSafeSkillName(): Boolean =
        isNotEmpty() && this != "." && this != ".." && !contains('/') && !contains('\\')

    /* ── 2.7.8 批次 5: 按索引装 / 升级 (带逐文件摘要) ──────────────────────────── */

    /**
     * 一个技能装/升级的结论
     *
     * 与 [Report] 分开是因为这一条要多说三件事: 装的是哪个版本、哪几个文件**被本地改过所以没盖**、
     * 哪几个文件取不到或摘要不对 —— 这三条正是"升级会不会吃掉主人改动"的全部证据
     */
    data class InstallReport(
        val skill: String,
        val version: String,
        val wrote: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val error: String = "",
    )

    /** 技能目录 (`$DSH_HOME/skills`) */
    private fun skillsDirectory(context: Context): File = File(File(context.filesDir, DshHost.HOME_DIR), SKILLS)

    /** 缓存下来那份索引 → 技能表 (不发网络请求: 设置页那一段是同步跑的) */
    fun index(context: Context): List<RemoteSkill> {
        val cached = File(File(context.filesDir, DshHost.HOME_DIR), CACHE)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        val parsed = cached?.let { parseIndex(it) }.orEmpty()
        if (parsed.isNotEmpty()) return parsed
        return bundled(context).map { entry ->
            RemoteSkill(entry.skill, entry.title, "", "", entry.apps, listOf(RemoteFile(SKILL_FILE, "")), "")
        }
    }

    /**
     * 按索引装一条 (新落点: 一个技能可以有多个文件)
     *
     * 三条规矩与"一键装"一致, 外加一条: **索引说得出摘要就核摘要** —— 取回来的字节与它写的那一份
     * 对不上时这一个文件不落地 (而不是"装个半新半旧的技能")
     */
    fun installFrom(context: Context, skill: RemoteSkill, force: Boolean = false): InstallReport {
        if (!skill.id.isSafeSkillName()) {
            return InstallReport(skill.id, skill.version, error = "名字不是目录名: ${skill.id}")
        }
        val directory = File(skillsDirectory(context), skill.id)
        if (File(directory, SKILL_FILE).isFile && !force) {
            return InstallReport(skill.id, skill.version, error = "${skill.id} 已经在技能目录里 (要换先删)")
        }
        val wrote = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val digests = mutableMapOf<String, String>()
        skill.files.forEach { file ->
            if (!file.path.isSafeRelativePath()) {
                failed += file.path
                return@forEach
            }
            val bytes = fetchBytes("skills/${skill.id}/${file.path}")
            if (bytes == null) {
                failed += file.path
                return@forEach
            }
            val digest = sha256Of(bytes)
            if (file.sha256.isNotEmpty() && digest != file.sha256) {
                failed += file.path
                return@forEach
            }
            val target = File(directory, file.path)
            val written = runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }.isSuccess
            if (written) {
                wrote += file.path
                digests[file.path] = digest
            } else {
                failed += file.path
            }
        }
        if (wrote.isNotEmpty()) writeMeta(directory, LocalMeta(skill.id, skill.version, digests))
        return InstallReport(skill.id, skill.version, wrote = wrote, failed = failed)
    }

    /**
     * 按索引**升级**一条: 只盖"没被本地改过"的那几个文件
     *
     * 判据只有一个: 本地文件的摘要与 `.lw-meta.json` 上那一份相等 → 是上游装上来的原样, 可以盖;
     * 不等或账上压根没有这一项 → **当成主人改过**, 不盖, 记进 skipped 让调用方去问
     * (`force = true` 才盖: 那个"确认覆盖"的对话框就是这一条)
     */
    fun upgradeFrom(context: Context, skill: RemoteSkill, force: Boolean = false): InstallReport {
        if (!skill.id.isSafeSkillName()) {
            return InstallReport(skill.id, skill.version, error = "名字不是目录名: ${skill.id}")
        }
        val directory = File(skillsDirectory(context), skill.id)
        if (!File(directory, SKILL_FILE).isFile) {
            return InstallReport(skill.id, skill.version, error = "${skill.id} 还没装过, 没有可升级的")
        }
        val meta = readMeta(directory)
        val wrote = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val digests = meta?.files.orEmpty().toMutableMap()
        skill.files.forEach { file ->
            if (!file.path.isSafeRelativePath()) {
                failed += file.path
                return@forEach
            }
            val target = File(directory, file.path)
            val local = target.takeIf { it.isFile }?.let { sha256Of(it) }
            val known = meta?.files?.get(file.path)
            if (!force && local != null && known != null && local != known) {
                // 主人改过 → 保留本地那一份, 账上继续记它的本地摘要 (下一次还是"改过")
                skipped += file.path
                return@forEach
            }
            val bytes = fetchBytes("skills/${skill.id}/${file.path}")
            if (bytes == null) {
                failed += file.path
                return@forEach
            }
            val digest = sha256Of(bytes)
            if (file.sha256.isNotEmpty() && digest != file.sha256) {
                failed += file.path
                return@forEach
            }
            if (local == digest) {
                unchanged += file.path
                digests[file.path] = digest
                return@forEach
            }
            val written = runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }.isSuccess
            if (written) {
                wrote += file.path
                digests[file.path] = digest
            } else {
                failed += file.path
            }
        }
        if (wrote.isNotEmpty()) writeMeta(directory, LocalMeta(skill.id, skill.version, digests))
        return InstallReport(skill.id, skill.version, wrote, unchanged, skipped, failed)
    }

    /** 装/升级那一句人话: 写了几个、跳过几个 (为什么跳)、坏在哪 */
    fun sentenceOf(report: InstallReport, op: String): String {
        if (report.error.isNotEmpty()) return report.error
        val version = if (report.version.isEmpty()) "" else " ${report.version}"
        val head = when (op) {
            "upgrade" -> if (report.wrote.isEmpty() && report.skipped.isEmpty()) {
                "${report.skill}$version 已经是最新的"
            } else {
                "${report.skill}$version 升级: 写了 ${report.wrote.size} 个文件" +
                    (if (report.unchanged.isEmpty()) "" else ", ${report.unchanged.size} 个没变") +
                    (if (report.skipped.isEmpty()) "" else ", ${report.skipped.size} 个被本地改过没盖 (" + report.skipped.joinToString(", ") + ")") +
                    (if (report.failed.isEmpty()) "" else ", ${report.failed.size} 个没取到 (" + report.failed.joinToString(", ") + ")")
            }
            else -> if (report.wrote.isEmpty()) {
                "${report.skill} 没装成" + (if (report.failed.isEmpty()) "" else ": " + report.failed.joinToString(", "))
            } else {
                "装了 ${report.skill}$version (${report.wrote.size} 个文件)" +
                    (if (report.failed.isEmpty()) "" else ", 有 ${report.failed.size} 个没取到 (" + report.failed.joinToString(", ") + ")")
            }
        }
        return head
    }


    /* ── 2.7.8 批次 5: 索引的第二种形状 + 版本与逐文件摘要 ───────────────────────── */

    /**
     * 索引里**一个文件**: 仓库里的相对路径, 与它的 sha256 (空串 = 索引没说, 那就只按内容写)
     *
     * 路径必须是**相对**的、不含 `..` —— 索引是外部数据, 它指到技能目录外面去这件事不能靠"应该不会"
     */
    data class RemoteFile(val path: String, val sha256: String)

    /**
     * 远端索引里的一条技能 (新形状 `skills[]`)
     *
     * 老形状 (`apps[]`, 一个包一条) 仍然收着: [remoteEntries] 那一条路一个字没改, 于是**旧的 Luwi
     * 与新的索引能并存**, 而新版只是多了 `version` / `description` / `files[]` 这几栏
     */
    data class RemoteSkill(
        val id: String,
        val title: String,
        val description: String,
        val version: String,
        val apps: List<String>,
        val files: List<RemoteFile>,
        val risk: String,
    )

    /**
     * 本地那一本账 (`$DSH_HOME/skills/<id>/.lw-meta.json`)
     *
     * 记的是"装上那一刻每个文件是什么摘要"。升级时它给出**唯一**能分辨"上游改了"与"主人改过"的
     * 判据: 本地文件的摘要 == 账上的 → 没被本地改过, 可以覆盖; 不等 → 主人动过, 不许悄悄盖掉
     */
    data class LocalMeta(val skill: String, val version: String, val files: Map<String, String>)

    private const val META = ".lw-meta.json"

    /**
     * 索引文本 → 技能表: **先认新形状 `skills[]`, 没有再退回老的 `apps[]`**
     *
     * 两条路都给同一份 [RemoteSkill], 于是上面那些 op 不必知道索引是哪一版。老形状没有版本与文件
     * 清单, 于是 version 是空串、files 只有 `SKILL.md` 一个 (摘要留空 = 不校验)
     */
    internal fun parseIndex(text: String): List<RemoteSkill> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val skills = root["skills"] as? JsonArray
        if (skills != null) {
            return skills.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!id.isSafeSkillName()) return@mapNotNull null
                val files = (item["files"] as? JsonArray).orEmpty().mapNotNull { one ->
                    val file = one as? JsonObject ?: return@mapNotNull null
                    val path = file["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (!path.isSafeRelativePath()) return@mapNotNull null
                    RemoteFile(path, file["sha256"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty())
                }.ifEmpty { listOf(RemoteFile(SKILL_FILE, "")) }
                RemoteSkill(
                    id = id,
                    title = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { id },
                    description = item["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    version = item["version"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    apps = jsonStrings(item["apps"]),
                    files = files,
                    risk = item["risk"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                )
            }
        }
        return remoteEntries(text).map { entry ->
            RemoteSkill(
                id = entry.skill,
                title = entry.title,
                description = "",
                version = "",
                apps = entry.apps,
                files = listOf(RemoteFile(SKILL_FILE, "")),
                risk = "",
            )
        }
    }

    private fun jsonStrings(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** 文件路径能不能落进技能目录 (纯函数): 相对、不含 `..`、不是绝对路径、不是那个账本自己 */
    internal fun String.isSafeRelativePath(): Boolean =
        isNotEmpty() &&
            !startsWith("/") &&
            !startsWith("\\") &&
            !contains('\\') &&
            !contains("..") &&
            split('/').none { it == "." || it.isEmpty() } &&
            this != META

    /** 搜一条技能: 空串给全部, 否则 id / 显示名 / 描述里有一个包含就算中 (纯函数, 不分大小写) */
    internal fun search(skills: List<RemoteSkill>, query: String): List<RemoteSkill> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return skills
        return skills.filter { skill ->
            skill.id.lowercase().contains(needle) ||
                skill.title.lowercase().contains(needle) ||
                skill.description.lowercase().contains(needle)
        }
    }

    /** 字节的 sha256, 小写十六进制 —— 与索引里那一栏同一个写法 */
    internal fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 一个文件的 sha256 (读不出来给 null: 调用方要能分清"没有这个文件"与"摘要是空") */
    internal fun sha256Of(file: File): String? =
        runCatching { sha256Of(file.readBytes()) }.getOrNull()

    /**
     * 技能正文的 frontmatter (`---` 夹着的那一段) → 键值表 (纯函数)
     *
     * 只认 `key: value` 这一层 (与 dsh 自己读技能时同一个颗粒度): 装之前核一次"它有名字与描述吗",
     * 坏的那一份当场拒, 而不是等下一轮模型加载技能时才发现
     */
    internal fun frontmatterOf(body: String): Map<String, String> {
        val lines = body.replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap()
        val found = mutableMapOf<String, String>()
        lines.subList(1, end + 1).forEach { line ->
            val at = line.indexOf(':')
            if (at <= 0) return@forEach
            val key = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim().trim('"', '\'')
            if (key.isNotEmpty() && value.isNotEmpty()) found[key] = value
        }
        return found
    }

    /** 本地那一本账 (没有 / 坏了都给 null: 升级时按"不知道有没有改过"处理) */
    internal fun readMeta(directory: File): LocalMeta? = runCatching {
        val text = File(directory, META).readText()
        val root = Json.parseToJsonElement(text).jsonObject
        val files = (root["files"] as? JsonObject).orEmpty().mapNotNull { (path, value) ->
            val digest = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
            if (digest.isEmpty()) null else path to digest
        }.toMap()
        LocalMeta(
            skill = root["skill"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            version = root["version"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            files = files,
        )
    }.getOrNull()

    /** 把那一本账写下来 (原子写: 先写 `.tmp` 再改名, 半份账比没有账更难查) */
    internal fun writeMeta(directory: File, meta: LocalMeta): Boolean = runCatching {
        directory.mkdirs()
        val json = buildJsonObject {
            put("skill", meta.skill)
            put("version", meta.version)
            put("at", System.currentTimeMillis())
            put("files", buildJsonObject { meta.files.forEach { (path, digest) -> put(path, digest) } })
        }
        val file = File(directory, META)
        val temp = File(directory, "$META.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            temp.delete()
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    /**
     * 桥上那两个 op
     *
     * `scan` 只读, `install` 才落地; 两个都回同一张表, 于是"装之前能看见会装什么"
     */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        val op = request["op"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val only = (request["skills"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val report = when (op) {
            "scan" -> scan(context)
            "install" -> {
                // 点名的那几个得真的在包里: 拼错一个名字就什么也不装, 而不是"装了另外几个"
                val unknown = only.filterNot { packaged(context, it) }
                if (unknown.isNotEmpty()) {
                    throw IllegalArgumentException("包里没有这几个技能: ${unknown.joinToString(", ")}")
                }
                install(context, only)
            }
            // **删**: 点名的那几个必须在 `$DSH_HOME/skills/` 里真的在 (与 install 同一套"不许糊弄")
            "remove" -> {
                val missing = only.filterNot { it in present(context) }
                if (only.isEmpty() || missing.isNotEmpty()) {
                    throw IllegalArgumentException("这几个技能不在技能目录里, 没删: ${missing.joinToString(", ")}")
                }
                remove(context, only)
            }
            // **索引那两条只读**: 一个网络请求都不发, 读的是缓存 (随包那份兜底)
            "index", "search" -> {
                val query = request["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val skills = search(index(context), query)
                val have = present(context)
                return buildJsonObject {
                    put("op", op)
                    put("ok", true)
                    put("count", skills.size)
                    putJsonArray("skills") {
                        skills.forEach { skill ->
                            add(
                                buildJsonObject {
                                    put("id", skill.id)
                                    put("name", skill.title)
                                    put("description", skill.description)
                                    put("version", skill.version)
                                    put("risk", skill.risk)
                                    put("installed", skill.id in have)
                                    put("localVersion", readMeta(File(skillsDirectory(context), skill.id))?.version.orEmpty())
                                    putJsonArray("apps") { skill.apps.forEach { add(it) } }
                                    putJsonArray("files") { skill.files.forEach { add(it.path) } }
                                },
                            )
                        }
                    }
                    put("text", if (skills.isEmpty()) "索引里没有匹配的技能" else "索引 ${skills.size} 条, 其中 ${skills.count { it.id !in have }} 条还没装")
                }
            }
            // **升级**: 只盖没被本地改过的那几个文件, 见 [upgradeFrom]
            "upgrade" -> {
                val known = index(context).associateBy { it.id }
                val unknown = only.filterNot { it in known }
                if (only.isEmpty() || unknown.isNotEmpty()) {
                    throw IllegalArgumentException("索引里没有这几个技能: ${unknown.joinToString(", ")}")
                }
                val force = request["force"]?.jsonPrimitive?.contentOrNull == "true"
                val reports = only.map { upgradeFrom(context, known.getValue(it), force) }
                return buildJsonObject {
                    put("op", op)
                    put("ok", reports.all { it.error.isEmpty() })
                    putJsonArray("skills") {
                        reports.forEach { report ->
                            add(
                                buildJsonObject {
                                    put("id", report.skill)
                                    put("version", report.version)
                                    putJsonArray("wrote") { report.wrote.forEach { add(it) } }
                                    putJsonArray("unchanged") { report.unchanged.forEach { add(it) } }
                                    putJsonArray("skipped") { report.skipped.forEach { add(it) } }
                                    putJsonArray("failed") { report.failed.forEach { add(it) } }
                                    put("error", report.error)
                                },
                            )
                        }
                    }
                    put("text", reports.joinToString("\n") { sentenceOf(it, "upgrade") })
                }
            }
            else -> return buildJsonObject {
                put("ok", false)
                put(
                    "error",
                    "skill 这个桥方法的 op 是这些: scan (看会装什么) / install (装) / remove (删)" +
                        " / index (索引) / search (搜索引) / upgrade (升级)",
                )
            }
        }
        return buildJsonObject {
            put("op", op)
            put("count", report.matches.size)
            put("wrote", buildJsonArray { report.wrote.forEach { add(it) } })
            put(
                "removed",
                buildJsonArray { if (op == "remove") report.wrote.forEach { add(it) } },
            )
            put("failed", buildJsonArray { report.failed.forEach { add(it) } })
            putJsonArray("matches") {
                report.matches.forEach { match ->
                    add(
                        buildJsonObject {
                            put("skill", match.skill)
                            put("title", match.title)
                            put("installed", match.present)
                            put("apps", buildJsonArray { match.hits.forEach { add(it) } })
                        },
                    )
                }
            }
            put("text", sentence(report, op))
        }
    }

    /** 那句给人看的话: 命中了哪几条、刚装了几条; 点名装的那一路不看"命中" */
    private fun sentence(report: Report, op: String): String {
        if (report.error.isNotEmpty()) return report.error
        val lines = report.matches.map { match ->
            val state = if (match.present) "已经在" else "缺着"
            "${match.skill} (${match.title}) $state —— 命中 ${match.hits.joinToString(", ")}"
        }
        val head = when (op) {
            "remove" -> "删了 ${report.wrote.size} 条" +
                (if (report.wrote.isEmpty()) "" else " (${report.wrote.joinToString(", ")})") +
                (if (report.failed.isEmpty()) "" else ", 有 ${report.failed.size} 条没删掉 (${report.failed.joinToString(", ")})")
            "install" -> if (report.wrote.isEmpty() && report.failed.isEmpty()) {
                "一条都不用装: 该在的都在了"
            } else {
                "装了 ${report.wrote.size} 条" +
                    (if (report.wrote.isEmpty()) "" else " (${report.wrote.joinToString(", ")})") +
                    (if (report.failed.isEmpty()) "" else ", 有 ${report.failed.size} 条没写成 (${report.failed.joinToString(", ")})")
            }
            else -> "命中 ${report.matches.size} 条, 其中 ${report.matches.count { !it.present }} 条还没装"
        }
        // 一句结论 + 命中的那几条明细; 一条都没命中时不摆空表, 只把那句结论说出来
        return if (lines.isEmpty()) head else head + "\n" + lines.joinToString("\n")
    }
}
