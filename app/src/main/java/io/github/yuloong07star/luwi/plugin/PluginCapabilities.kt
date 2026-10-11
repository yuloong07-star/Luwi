package io.github.yuloong07star.luwi.plugin

/**
 * 能力表 (协议第 5 节)
 *
 * 插件只能从这一张表里挑, **表外的能力一律拒绝安装** —— 忽略未知能力会造出"我以为只给了很小的权限"。
 * 每条还带一个 [Capability.wired]: 协议是先定的, 能力是分批接通的, 于是"声明可装"与"这一版真能跑"
 * 是两件事。没接通的那一条在调用时回一句点名的话, 不假装成功也不静默忽略
 *
 * `tools/check-plugins.mjs` 拿这张表去比 `docs/LW-软件插件协议.md` 第 5 节那两张表
 */
internal object PluginCapabilities {

    enum class Level { NORMAL, DANGEROUS }

    data class Capability(
        /** 表里的名字; 带参数的两条 (见 [parameterized]) 只是前缀 */
        val name: String,
        val level: Level,
        val summary: String,
        /** P0/P1 真接通的为 true, 其余只可声明 */
        val wired: Boolean,
    )

    /**
     * 带参数的两条: 声明的是 `files.read:<路径前缀>` 与 `net.http:<域名>`
     *
     * 表里那两条名字后面跟着一个冒号就是记号; 校验时按前缀认, 具体那一截留着给人看
     */
    val parameterized: Set<String> = setOf("files.read:", "net.http:")

    val ALL: List<Capability> = listOf(
        Capability("device.read", Level.NORMAL, "电量 / 网络 / 存储 / 设备信息", true),
        Capability("sensor.read", Level.NORMAL, "传感器与光感", true),
        Capability("notify.post", Level.NORMAL, "发通知 / 横幅", true),
        Capability("speech.speak", Level.NORMAL, "让 LW 念一句话", true),
        Capability("clipboard", Level.NORMAL, "剪贴板读 / 写", true),
        Capability("files.own", Level.NORMAL, "只读自己那个插件目录", true),
        Capability("location.read", Level.NORMAL, "位置 (粗 / 精由用户在设置里另给)", false),
        Capability("files.read:", Level.NORMAL, "读指定范围 (例如 /sdcard/DSH)", false),
        Capability("net.http:", Level.NORMAL, "只允许访问列出的域名", false),
        Capability("ui.theme", Level.NORMAL, "提供浮标外观", true),
        Capability("ui.panel", Level.NORMAL, "提供一页声明式面板", false),
        Capability("ui.widget", Level.NORMAL, "往宿主槽里推桌面组件的内容", true),
        Capability("screen.read", Level.DANGEROUS, "截图 / 读控件树 / OCR", false),
        Capability("screen.write", Level.DANGEROUS, "点击 / 滑动 / 打字 / 起应用 / 建虚拟屏", false),
        Capability("input.key", Level.DANGEROUS, "按键与组合键", false),
        Capability("system.write", Level.DANGEROUS, "改系统设置 (亮度 / 音量 / 超时…)", false),
        Capability("camera", Level.DANGEROUS, "用摄像头取帧", false),
        Capability("media", Level.DANGEROUS, "媒体库、播放、拍照", false),
        Capability("session.post", Level.DANGEROUS, "把一句话投进某一场会话", true),
        Capability("session.create", Level.DANGEROUS, "开一场新的会话", false),
        Capability("session.read", Level.DANGEROUS, "读会话里的回答", false),
        Capability("privileged.key", Level.DANGEROUS, "借特权通道按键 (root / Shizuku)", false),
        Capability("privileged.shell", Level.DANGEROUS, "借特权通道执行命令", false),
    )

    private val byName: Map<String, Capability> = ALL.associateBy { it.name }

    /** 声明的那一条认不认: 表里原样有, 或者是两条带参数的之一 (且冒号后面非空) */
    fun find(declared: String): Capability? {
        byName[declared]?.let { return it }
        val parameterizedHit = parameterized.firstOrNull { declared.startsWith(it) } ?: return null
        return if (declared.length > parameterizedHit.length) byName[parameterizedHit] else null
    }

    /** 这条声明是不是"敏感档" */
    fun isDangerous(declared: String): Boolean = find(declared)?.level == Level.DANGEROUS

    /** 这一版接通了没有 */
    fun isWired(declared: String): Boolean = find(declared)?.wired == true

    /** 给人看的那一句 (装在通知与拒绝话术里) */
    fun summaryOf(declared: String): String = find(declared)?.summary ?: declared
}
