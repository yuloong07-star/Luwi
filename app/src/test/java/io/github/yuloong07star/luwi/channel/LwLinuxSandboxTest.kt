package io.github.yuloong07star.luwi.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Linux 沙盒那两条"形状"的判据
 *
 * 这一份**不碰设备**, 也一个字符都不 exec: [LwLinuxSandbox.prootCommand] 是"命令形状"本身 (它的
 * 每一样都写死在特权那一侧, 只有 work 与命令文本来自外面), 而 [LwLinuxSandbox.workShapeProblem]
 * 是那道门的一半. 要挡的是两种"参数变成别的东西": 一个带冒号的 work 把 `-b` 的值切成两半, 一段
 * 命令文本从末尾跑到 proot 的参数位上去 —— 两条都能在这一层量出来, 不用上真机
 */
internal class LwLinuxSandboxTest {

    private val libraryDir = "/data/app/~~abcdef==/io.github.yuloong07star.luwi-xyz==/lib/arm64"
    private val work = "/sdcard/DSH/project"
    private val command = "apk add --no-cache git && git --version"

    @Test
    fun `命令行是写死的那一套, 顺序与常量都对得上`() {
        val argv = LwLinuxSandbox.prootCommand(libraryDir, work, command)
        assertEquals("setsid", argv.first())
        assertTrue(argv.contains("PROOT_LOADER=$libraryDir/${LwLinuxSandbox.LOADER_NAME}"))
        assertTrue(argv.contains("PROOT_TMP_DIR=${LwLinuxSandbox.TEMP_DIRECTORY}"))
        assertTrue(argv.contains("$libraryDir/${LwLinuxSandbox.PROOT_NAME}"))
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("--kill-on-exit"))
        // 根与 work 的落点: `-r <rootfs>` 与 `-b <work>:/work`, 值必须**紧跟**在旗标后面
        val root = argv.indexOf("-r")
        assertEquals(LwLinuxSandbox.ROOTFS_DIRECTORY, argv[root + 1])
        val bind = argv.indexOf("-b")
        assertEquals("$work:/work", argv[bind + 1])
        // 设备的 binds 也在这条线上, 一个不少
        listOf("/dev/null", "/dev/urandom", "/dev/pts").forEach { device ->
            assertTrue("$device is not bound", (bind until argv.size - 1).any { "$device" == argv[it + 1] })
        }
    }

    @Test
    fun `命令文本只出现在最后那一个位置`() {
        val argv = LwLinuxSandbox.prootCommand(libraryDir, work, command)
        // /bin/sh -lc <命令> 收尾: 命令是**最后一个元素**, 而 proot 已经不认识它后面的东西了
        assertEquals(listOf("/bin/sh", "-lc", command), argv.takeLast(3))
        assertEquals(1, argv.count { it == command })
    }

    @Test
    fun `work 只进 -b 那一个值`() {
        val argv = LwLinuxSandbox.prootCommand(libraryDir, work, command)
        assertEquals(1, argv.count { it.contains(work) })
        assertTrue(argv.contains("$work:/work"))
    }

    @Test
    fun `work 的形状 —— 相对的、空的、带冒号的、带控制字符的一律拒`() {
        assertNotNull(LwLinuxSandbox.workShapeProblem(""))
        assertNotNull(LwLinuxSandbox.workShapeProblem("   "))
        assertNotNull(LwLinuxSandbox.workShapeProblem("sdcard/DSH"))
        assertNotNull(LwLinuxSandbox.workShapeProblem("./DSH"))
        // 冒号能把 `-b /a:/work` 变成 `-b /a:/b:/work` —— 这一条就是要挡的那种输入
        assertNotNull(LwLinuxSandbox.workShapeProblem("/sdcard/DSH:/etc"))
        assertNotNull(LwLinuxSandbox.workShapeProblem("/sdcard/DSH\n-r"))
        assertNotNull(LwLinuxSandbox.workShapeProblem("/sdcard/DSH\u0000"))
    }

    @Test
    fun `work 的形状 —— 一个正常绝对路径过关`() {
        assertNull(LwLinuxSandbox.workShapeProblem("/sdcard/DSH/我的项目"))
        assertNull(LwLinuxSandbox.workShapeProblem("/sdcard/DSH/a b/c"))
    }

    @Test
    fun `安装路径带等号时命令形状也要立得住`() {
        // 真机的安装路径长这样: `...luwi-ztX...myw==/lib/arm64` —— 含 `==`。那份 argv 里,
        // env 看见的第一个非赋值参数必须是 /system/bin/sh, 不能是 proot 的路径: 直接交路径过去时
        // env 会把 `名字==值` 当成一次环境赋值吃掉, 报 `env: exec -0: No such file or directory`
        // (2026-10-12 在 Redmi K50 上实测踩到的这一条), proot 因此永远起不来
        val crooked = "/data/app/~~qUUnh4fQxOCV8Y3wPH88gw==/io.github.yuloong07star.luwi-ztX_uW4btUxSYX6JE9wmyw==/lib/arm64"
        val argv = LwLinuxSandbox.prootCommand(crooked, work, command)
        val afterEnv = argv.dropWhile { it != "env" }.drop(1)
        // env 的语义: 先吃选项 (`-i`), 再吃"名字=值"的赋值, 剩下的第一个就是它要 exec 的命令
        val commandSlot = afterEnv.dropWhile { it.startsWith("-") || it.contains('=') }.first()
        assertEquals("/system/bin/sh", commandSlot)
        assertEquals(
            "$crooked/${LwLinuxSandbox.PROOT_NAME}",
            afterEnv[afterEnv.indexOf(commandSlot) + 3],
        )
    }

    @Test
    fun `du 的账在 Permission denied 行后面也要读得到`() {
        // 2026-10-12 真机实测: 沙盒在跑的时候 rootfs 里有 proot 刚摆下的 bind 落点 (000),
        // du 的合并流是"两条错误行在前、计数行在后" —— 旧的"只解析整个输出开头"在这里解析成
        // null, 而回执再把 null 写成 0, 设置页于是在运行期间说"0 B"
        val duringRun = "du: /data/local/tmp/lwlinux/rootfs/dev/pts: Permission denied\n" +
            "du: /data/local/tmp/lwlinux/rootfs/work: Permission denied\n" +
            "9096\t/data/local/tmp/lwlinux/rootfs\n"
        assertEquals(9096L, LwLinuxSandbox.sizeKilobytesOf(duringRun))
        // 空闲时那一行还是老样子, 一条数字行直接命中
        assertEquals(9096L, LwLinuxSandbox.sizeKilobytesOf("9096\t/data/local/tmp/lwlinux/rootfs"))
        // 一个数字都没有才是真的问不出来 (空回执 / 纯错误输出)
        assertNull(LwLinuxSandbox.sizeKilobytesOf(""))
        assertNull(LwLinuxSandbox.sizeKilobytesOf("du: /data/local/tmp/lwlinux/rootfs: Permission denied"))
    }

    @Test
    fun `组长记账目录不在 reset 会删掉的那棵树里`() {
        // 2026-10-12 真机逮到: pid 文件原本住在 `$BASE_DIRECTORY/tmp` 里, 而 reset 的第一步就是
        // `rm -rf` 整棵 base —— 删得快时读它的轮询还没看见文件就没了, waitForLeader 空等满 2 s
        // 兜底, reset 的墙钟于是 1.0 s / 2.9 s 随机跳. 记账目录必须在被删的那棵树外面
        assertFalse(LwLinuxSandbox.RUN_DIRECTORY.startsWith(LwLinuxSandbox.BASE_DIRECTORY + "/"))
    }
}
