# R8 与那几条"按名字找类"的路
#
# 2026-10-05 加, 起因是 1.2.0 一开 R8, 特权通道整条断掉 —— 现象是 `:lw_shizuku` 进程在
# `AndroidRuntime::startReg` 之后直接 SIGABRT, tombstone 里那句是:
#   ClassNotFoundException: io.github.yuloong07star.luwi.channel.LwServiceStarter
# 也就是说这个类**必须按原名留着**, 而不是"能跑到就行"

# 1) 特权进程的两个类都是**按名字**拿到的, 名字写在字符串常量里
#    (`RemoteServiceConnectorBackend.STARTER_CLASS` / `SERVICE_CLASS`), 由 liblauncher.so 交给
#    app_process。它们没有任何 Java 引用者, 所以 R8 眼里就是死代码:
#      - 入口类被改名/删掉 → app_process 在 startReg 之后 ClassNotFoundException → SIGABRT
#      - 服务类被改名 → 入口能进 (日志 "starter entered"), 但 loadClass 失败 → "the service
#        could not be created, exiting" → 特权通道永远连不上
#    两条都实测过 (2026-10-05), 所以这里整包保名字, 不再逐个列
-keep class io.github.yuloong07star.luwi.channel.** { *; }
-keepclassmembers class io.github.yuloong07star.luwi.channel.LwServiceStarter {
    public static void main(java.lang.String[]);
}

# 2) sherpa-onnx 的 AAR 里那份 proguard.txt 是**空的**, 而它的 native 方法是按类名/方法名绑的
#    (`com.k2fsa.sherpa.onnx.**`, 122 个类)。不保名字就是 UnsatisfiedLinkError 或者更糟
-keep class com.k2fsa.sherpa.onnx.** { *; }

# 3) 端侧 OCR 那套 ONNX Runtime 同样是 JNI 绑定 (`ai.onnxruntime.**`)
-keep class ai.onnxruntime.** { *; }

# 4) 特权通道依赖的两家再保一层名字 (它们自己有 consumer 规则, 这里只是不把话说满)
-keep class dev.rikka.shizuku.** { *; }
-keep class com.topjohnwu.superuser.** { *; }

# 5) LW 插件那份对外 API (`lwplugin-api`) 是**给插件调**的, 应用自己不一定调得到 —— R8 会把
#    没有应用侧引用者的方法与内部类剪掉, 于是插件 (inline 的 dex, 或伴侣 APK) 在设备上撞
#    NoSuchMethodError。2026-10-11 实测到的就是这一条: `ILwPluginContext.Companion.asInterface`
#    与 `ILwPluginContext$Proxy` 被剪掉, inline 插件的 attach 直接崩在 `:plugin` 进程里
#    (日志里那句是 `NoSuchMethodError ... asInterface ... appears in base.apk`), 而 describe 那一
#    步好好的 —— 那种"一半好一半崩"最费时间。整包保名字: 哪几个方法插件会调是我们说了算,
#    不是"应用这一侧有没有引用者"说了算
-keep class io.github.yuloong07star.luwi.plugin.api.** { *; }
