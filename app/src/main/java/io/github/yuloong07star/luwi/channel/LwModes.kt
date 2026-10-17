package io.github.yuloong07star.luwi.channel

import android.content.Context
import android.util.Log
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.CustomPresets
import io.github.yuloong07star.luwi.tool.LwCamera
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.wake.WakeWordState
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 模式: 手机模式 / 视频模式
 *
 * **识屏模式在 2.5.0 批次 4 摘掉了**: "看手机自己那块屏"本来就是手机模式里 display 0 的那条路
 * (看与动都落在那一块上), 而"用户得先切一个模式, 模型才看得见屏"正是这一批要消掉的心智负担 ——
 * 现在指代不明的那一句话在投递前自动附一张主屏截图 (宿主插件那一侧), 模式本身不需要了。
 * 读到 `.active` 里还写着 `screen` 的老机器由 [retire] 迁回手机模式
 *
 * **只换提示词, 不换工具也不换预设**: dsh 拒绝让另一个预设接管一个已经起过轮次的会话
 * (`agent-preset/locked`), 而助手的提示词是 provider、**每一步组装都重读文件** (四环链, 见可行性稿
 * 3.1.3)。所以"中途换人设"的做法就是把另一份正文 cp 进 `dsh-custom-mode` 那个助手的 `prompt.md`,
 * 下一步模型请求读到的就是新内容 —— 不用重启、不用新开会话、也不动 dsh 的预设闸
 *
 * 三份正文随 APK 发 (`assets/modes/<名字>.md`), 首启落到 `$DSH_HOME/modes/`, 落在那里是因为主人
 * 可以自己改 —— 改完下一次切过去就用新的那份
 *
 * **一次切换 = 一次桥调用** (`mode`): 提示词、摄像头、常驻语音三件事都在 [set] 里做完, 而设备上
 * 三个对应的脚本 (`phone.sh` / `video.sh` / `screen.sh`) 各是同一个切换的另一扇门 —— 切模式时**只跑
 * 对应的那一个**, 别的不做 (2026-10-06 主人的口径: 这样才快)
 *
 * 与前缀是不是"预设"无关: 只有 **`dsh-custom-mode` 那个助手** (预设显示名 `Luwi`) 读这份文件, 别的
 * 预设 (探索模式那一份) 的人设写在自己的 YAML 里, 切文件对它们没有影响 —— 手机/视频那两份 preset 已在
 * 2026-10-18 下线 (它们本来就是同一套提示词的第二个副本)
 */
internal object LwModes {

    /** 手机模式: 默认就是它, 能看能动 */
    const val PHONE = "phone"

    /** 视频模式: 用本机摄像头 (Camera2 直连) 抓帧识图 */
    const val VIDEO = "video"

    /** 两个模式, 顺序就是 seed 与报出去 (`list`) 的顺序 */
    val ALL = listOf(PHONE, VIDEO)

    /**
     * 退役的模式名: 识屏模式 (批次 4)
     *
     * 它不再出现在 [ALL] 里, 也不再有正文与脚本, 但**老机器上的 `.active` 里可能还写着它**, 而说惯了的
     * 人嘴上也还会说出来 —— 前者由 [retire] 迁回手机模式, 后者由宿主插件那张命令表把"退出识屏模式"
     * 当收工那一条 (见 `host-plugin/index.mjs` 的 `VOICE_COMMANDS`)
     */
    const val SCREEN_RETIRED = "screen"

    /** 两个模式各自的显示名, 报给模型时用它 */
    private val NAMES = mapOf(
        PHONE to R.string.mode_phone,
        VIDEO to R.string.mode_video,
    )

    /** 旧名字: 可行性稿里写的是 `assistant`, 留着当别名免得主人说惯了 */
    private const val PHONE_ALIAS = "assistant"

    private const val MODES_DIR = "modes"
    private const val ACTIVE = ".active"
    private const val CUSTOM_PROMPT = ".agent-presets/custom/prompt.md"

    /** 日志标记: 只在占用表清不掉时说话 (那不是切换失败, 不该弹任何东西给主人) */
    private const val TAG = "LwModes"

    /** `$DSH_HOME/modes`, 三份正文、那几个脚本与 `.active` 都在这儿 */
    fun modesDir(context: Context): File = File(dshHome(context), MODES_DIR)

    /** 那个助手的提示词文件, 切模式就是覆盖它 */
    fun promptFile(context: Context): File = File(dshHome(context), CUSTOM_PROMPT)

    /**
     * 现在在哪一个模式: 读 `.active`, 读不到就当手机模式 (默认)
     *
     * **退役的模式名一律当手机模式**: 老机器升级上来时 `.active` 里还写着 `screen`, 而那时那句"我在
     * 识屏模式"已经是一句不成立的话 —— 与其把它报出去 (界面上那几处会去查 [NAMES], 查不到又落回手机
     * 模式), 不如在这一处就收干净
     */
    fun active(context: Context): String {
        return resolve(rawActive(context))
    }

    /**
     * `.active` 里读到的那一串该当成哪个模式
     *
     * 纯函数 (不碰 Context), 因为这一条判据要单测: 老机器上写着 `screen` 的那一份, 空文件, 两边带空白的
     * 那一份, 以及一个我们不认识的名字 —— 四种都在这一个函数里定下来 (`LwModesTest`)
     */
    fun resolve(stored: String?): String {
        val name = stored?.trim()?.lowercase().orEmpty()
        return if (name.isEmpty() || name == SCREEN_RETIRED) PHONE else name
    }

    /** `.active` 里原样写着什么 (空的 / 文件不在就是空串): [retire] 要的正是"没被 [resolve] 修过"的那一份 */
    private fun rawActive(context: Context): String =
        File(modesDir(context), ACTIVE).takeIf { it.isFile }?.readText()?.trim()?.lowercase().orEmpty()

    /**
     * 把三份正文与那几个脚本从 APK 里放出来
     *
     * **正文只写缺的那些**: 主人改过的那份不能被 APK 里的覆盖回去, 否则"可以自己改"就是一句空话
     * 已在设备上的 (手推的、主人改的) 一律留着
     *
     * **脚本每次都覆盖**, 与正文相反: 脚本是代码的一部分, 修一次就得跟着装机走进设备 —— 只写缺的
     * 会让设备上那份旧拷贝永远留在那儿。这一条也是"切模式那一步不许调 [seed]"的理由: 每次切换都
     * 重写几十 KB 的脚本是白花的
     */
    fun seed(context: Context): List<String> {
        val directory = modesDir(context)
        directory.mkdirs()
        val written = mutableListOf<String>()
        ALL.forEach { name -> if (ensureBody(context, name)) written += name }
        // 那几份 `切模式` 脚本就是每个模式各自的**整个切换** (2026-10-06): 切模式时只跑对应的那一个,
        // 别的什么都不做; camera.sh 不是切换, 它是视频模式里换镜头那一下 (走同一条回环桥)
        listOf("camera.sh", "phone.sh", "video.sh").forEach { name ->
            if (copyScript(context, directory, name)) written += name
        }
        // 退役的脚本就地删掉 (留着就是设备上一份能跑起来的旧链路, 或者同一件事的第二扇门):
        // `mode.sh` 是"建虚拟屏 + 起相机应用"的旧切换链路 (2026-10-05 摘掉); `voice-input.sh` 是切模式
        // 的语音那一半 —— 它现在整个并进了那三个脚本里 (2026-10-06)
        //
        // 批次 4 又添了两份: `screen.sh` 与 `screen.md`。识屏模式没了, 那块屏现在由手机模式管; 正文
        // 也一并删 (它是"你处在识屏模式"那一份, 留着只会让读到它的人以为还有这个模式)
        listOf("mode.sh", "voice-input.sh", "screen.sh", "screen.md").forEach { name ->
            val retired = File(directory, name)
            if (retired.isFile) runCatching { retired.delete() }
        }
        return written
    }

    /**
     * 只放这一份正文 (缺了才写), 回"这一次写了没有"
     *
     * 与 [seed] 分开是因为**切模式那一步不该做整套 seed**: 那个函数每次都把几个脚本从 APK 里重写
     * 一遍, 而它排在切换里就是每次切都白写几十 KB —— 切换要快, 这条账是白花的
     */
    private fun ensureBody(context: Context, mode: String): Boolean {
        val target = File(modesDir(context), "$mode.md")
        if (target.isFile && target.length() > 0) return false
        modesDir(context).mkdirs()
        return runCatching {
            context.assets.open("$MODES_DIR/$mode.md").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
    }

    /** 一个脚本: 打开可执行位, 回"放成了没有" (放不成不是致命的, 回执里不报它) */
    private fun copyScript(context: Context, directory: File, name: String): Boolean = runCatching {
        val script = File(directory, name)
        context.assets.open("$MODES_DIR/$name").use { input ->
            script.outputStream().use { output -> input.copyTo(output) }
        }
        script.setExecutable(true)
    }.isSuccess

    /**
     * 首启把默认模式落到实处
     *
     * 没记过 `.active` 就按**手机模式**写一次: 不写的话, 那个助手的提示词还是插件自己 seed 的默认
     * 文本 —— 主人会以为装上了手机模式, 其实读到的不是我们这两份里的任何一份
     */
    fun ensureDefault(context: Context) {
        seed(context)
        if (!File(modesDir(context), ACTIVE).isFile) set(context, PHONE) else retire(context)
    }

    /**
     * 退役模式留下的记号: `.active` 里还写着 `screen` 的机器迁回手机模式 (批次 4)
     *
     * 走 [set] 而不是只改那一个文件, 因为**提示词也要跟着换**: 那个助手的 `prompt.md` 里现在放着识屏
     * 那一份正文, 而那一份说的是一套已经不存在的东西。迁不成也不该让启动失败 (目录只读、被人动过都
     * 可能), 所以只记一行日志 —— 那几个读数 (`lw_mode status`、界面上那个模式名) 本来就已经把退役的
     * 名字当手机模式 ([active])
     */
    fun retire(context: Context) {
        if (rawActive(context) != SCREEN_RETIRED) return
        runCatching { set(context, PHONE) }
            .onFailure { Log.w(TAG, "the retired mode could not be migrated: ${it.message}") }
    }

    /**
     * 切模式: 把选中的那份正文写进那个助手的 `prompt.md`
     *
     * 覆盖提示词而**不动** `preset.yml` / `agent.cordis.yml`: 那些是助手的装配, 与"现在是谁"无关
     */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        val asked = request["mode"]?.toString()?.trim('"')?.trim()?.lowercase().orEmpty()
        return when (asked) {
            "", "status" -> status(context)
            "list" -> buildJsonObject {
                put("modes", ALL.joinToString(", "))
                put("active", active(context))
            }

            else -> set(context, asked)
        }
    }

    /**
     * 切模式的一条 Kotlin 入口 (通道那条 [dispatch] 是给桥、脚本与插件用的)
     *
     * 球上"这一点点的是「正在听」, 而常驻语音开着时顺手把视频模式也收掉"走这一条 —— 与 `lw_mode`、
     * 设备上那几个脚本**同一个 [set]**, 不是第二套做法 (2026-10-10 主人: "在 ball 新增一个常驻语音
     * 状态 … 单击关闭后也关闭视频模式")
     */
    internal fun switchTo(context: Context, mode: String): JsonObject = set(context, mode)

    /**
     * 切模式: **一次调用做完这一个模式要的全部事**
     *
     * 三笔账都在这里, 顺序是刻意的:
     *
     * 一、语音那一半 ([LwWakeWord.resident]): 只有视频模式要它 (主人 2026-10-06 定的口径 —— 常驻语音
     *     只允许视频模式打开)。它排在写提示词之前, 因为提示词那一步可能因为目录不存在而做不成, 而
     *     "进去就听不听得见"不该跟着那件事一起失效 —— 那正是"切了模式却要再喊一声"这个毛病
     * 二、写提示词 + `.active`
     * 三、相机那一半 ([LwCamera.warmUp] / [LwCamera.coolDown]): **只有提示词真的换成了才动它** ——
     *     没换成就是还站在原来那个模式里, 那时把相机收掉才是错的。两半都**不等人** (开一条相机链要
     *     几百毫秒, 排在回执里等它就是"说完切模式之后卡一下"), 所以回执说的是"正在开 / 正在收",
     *     而真相由后来者拿 (`lw_look` 自己会等那把锁, 见 [LwCamera.warmUp])
     *
     * 这三件事**从前是插件那一侧拼出来的** (先调 `mode`, 再调 `camera`), 那样一次切换要两趟往返,
     * 而且语音那一半还要在里面白等 600 ms。现在切模式走到这里就到底了, 插件与那几个设备端脚本都只发
     * 一条命令 (2026-10-06 主人的口径: 切模式只跑对应的那一个脚本, 别的什么都不做)
     */
    private fun set(context: Context, asked: String): JsonObject {
        val mode = when (asked) {
            PHONE, PHONE_ALIAS -> PHONE
            VIDEO -> VIDEO
            // 识屏模式 (批次 4 摘掉): 那一句"切到识屏模式"要说得出它现在是什么, 而不是一句
            // "unknown mode" —— 那块屏一直在, 只是它归手机模式管
            SCREEN_RETIRED -> return buildJsonObject {
                put("switched", false)
                put("mode", PHONE)
                put("name", context.getString(NAMES.getValue(PHONE)))
                put(
                    "detail",
                    "there is no screen mode any more: the phone's own screen (display 0) is looked" +
                        " at and acted on in phone mode, so nothing was switched. Look at it with" +
                        " lw_screenshot / lw_ui and act on it with lw_tap and the rest.",
                )
            }
            else -> return buildJsonObject {
                put("switched", false)
                put("detail", "unknown mode \"$asked\": this build has ${ALL.joinToString(" and ")}")
            }
        }
        // 只放这一份正文, **不做整套 seed**: 那几个脚本每次覆盖是"装了新版本要跟着走进设备"的意思,
        // 排在切换里就是每次切换都白写几十 KB (见 [seed])
        ensureBody(context, mode)
        val source = File(modesDir(context), "$mode.md")
        if (!source.isFile) {
            return buildJsonObject {
                put("switched", false)
                put(
                    "detail",
                    "the body of $mode is missing: ${source.absolutePath} is not there",
                )
            }
        }
        // 唤醒词那一个许可仍然一个字都不写 (主人 2026-10-05 的口径: 一个许可都不许自己打开):
        // 它是主人在设置页上的决定, 这里只把它读出来照着报一句
        //
        // 类型写在这里而不是让它推: 上面那条会抛出 `null to "..."` (第一个 null 是"不知道"), 两边的
        // 公共父类型是 `Pair<Boolean?, String?>`, 不写清楚的话 [refused] 那个签名就对不上
        val resident: Pair<Boolean?, String?> =
            runCatching { LwWakeWord.resident(context, mode == VIDEO, settle = false) }
                .getOrElse { error ->
                    null to "the resident voice chain could not be switched: ${error.message ?: error}"
                }
        // parentFile 是可空的 (根目录上的文件就没有): 先拿在手里判一次, 别在后面每处都写 `!!`
        val target = promptFile(context)
        val assistant = target.parentFile
        if (assistant == null) {
            return refused(mode, resident, "there is nowhere to write the prompt: ${target.path} has no directory")
        }
        // **缺目录就建**: 这个目录是那个助手的落脚处, 老机器上有 (装过旧 preset), 新装的机器上没有 ——
        // 没有就什么都不写等于"模式切不了, 而工具只说一句 not installed"。建出来是最小的一步, 而它
        // 仍然可能在别的 ROM 上失败 (只读挂载之类), 所以下面写文件那一步照样要判
        if (!assistant.isDirectory) runCatching { assistant.mkdirs() }
        val written = runCatching {
            // 先把旧的留一份: 这个文件是那个助手的全部人设, 覆盖错了就没有第二份
            if (target.isFile) target.copyTo(File(assistant, "prompt.md.before-$mode"), overwrite = true)
            target.writeText(source.readText())
            File(modesDir(context), ACTIVE).writeText(mode)
            target.length()
        }.getOrElse { error ->
            return refused(
                mode,
                resident,
                "the prompt for $mode could not be written to ${target.absolutePath}:" +
                    " ${error.message ?: error}",
            )
        }
        // **换模式 = 放弃相机** (批次 3): 相机占用表由宿主插件写 (见 [CameraOwner]), 而"换人设"这件事
        // 那四扇门 (球菜单 / 语音命令 / 设备脚本 / `lw_mode`) 全都收在这一处 —— 所以清表也只写在这里,
        // 谁切的都一样。切进视频模式时那一场会在切换返回之后重新落账 (插件那一边的 `lw_mode`)
        runCatching { CameraOwner.release(context) }
            .onFailure { Log.w(TAG, "the camera owner table could not be cleared: ${it.message}") }
        val camera = runCatching {
            if (mode == VIDEO) LwCamera.warmUp(context) else LwCamera.coolDown(context)
        }.getOrElse { error -> "the camera could not be switched: ${error.message ?: error}" }
        return buildJsonObject {
            put("switched", true)
            put("mode", mode)
            put("name", context.getString(NAMES.getValue(mode)))
            put("characters", written)
            put("camera", camera)
            put("voice", resident.second ?: "")
            put("wakeWordListening", WakeWordState.listening)
            put("residentVoice", resident.first ?: (mode == VIDEO))
            put("voiceActive", WakeWordState.voiceActive)
            put(
                "detail",
                "$mode is in place: the next model step of a session on the custom-mode assistant reads it",
            )
        }
    }

    /** 切换没成时的回执: 语音那一半可能已经动过了, 所以说出来它现在是什么样 */
    private fun refused(mode: String, resident: Pair<Boolean?, String?>, why: String): JsonObject =
        buildJsonObject {
            put("switched", false)
            put("mode", mode)
            put("residentVoice", resident.first ?: (mode == VIDEO))
            put("voice", resident.second ?: "")
            put("detail", why)
        }

    private fun status(context: Context): JsonObject {
        val mode = active(context)
        val target = promptFile(context)
        return buildJsonObject {
            put("mode", mode)
            put("name", label(context, mode))
            put("available", ALL.joinToString(", "))
            put("promptFile", target.absolutePath)
            put("promptWritten", target.isFile)
            put("modesDirectory", modesDir(context).absolutePath)
            // 那个助手与它那几份预设声明齐了没有 (2026-10-09): 新机上"语音开不了新会话"就是这一行
            // 报出来的东西; 判据与首启安装那一步同源 (见 [CustomPresets])
            put("customPreset", CustomPresets.describe(context))
        }
    }

    /**
     * 一个模式的显示名, 认不出的名字一律当手机模式
     *
     * **界面上那几处 (球, 通知栏) 走这一条**, 而不是自己写 `if (mode == VIDEO) ... else ...` ——
     * 那种二选一在第三个模式出现之后会静默地把识屏模式报成「手机模式」(2026-10-06)
     */
    fun label(context: Context, mode: String? = null): String =
        context.getString(NAMES[mode ?: active(context)] ?: R.string.mode_phone)

    private fun dshHome(context: Context): File = File(context.filesDir, DshHost.HOME_DIR)
}
