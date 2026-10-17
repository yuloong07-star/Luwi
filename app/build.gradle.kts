import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The dsh host tree is a build product of the submodule rather than of this repository, and the
// APK ships it as one archive the app extracts on first run, so the build grows two steps here
// instead of a checked-in directory. Both are keyed on the submodule pin and on its sources, so
// only a dsh change pays for the rebuild: a plain :app:assembleDebug reuses what it already has
// Edits to submodule files outside the globs below do not invalidate the tree; rerun with
// `--rerun-tasks` (or run tools/pack-host.mjs by hand and push it, see tools/push-host.mjs)
val dshCheckout = rootProject.layout.projectDirectory.dir("third_party/deepseek-harness")
val hostTree = layout.buildDirectory.dir("host-tree")
val hostAssets = layout.buildDirectory.dir("generated/host-assets")

// 首启要落到 $DSH_HOME 里的那两项 (四份技能 + 两条样例快捷指令) 也是构建产物: 源在仓库根的
// `skills/` 与 `quick-commands/`, 拷成 assets 的形状由下面的 copySeedAssets 做。**显式列这几份,
// 不整目录拷** —— `skills/android-device-control` 这一版不随包 (主人 2026-10-08 选的),
// 要收口时把它加进那张表就行, 见 docs/DSH-LW-2.5.0-批次7-开发计划.md
val seedAssets = layout.buildDirectory.dir("generated/seed-assets")

/** 随包发的技能: 目录名 = frontmatter 里的 `name` */
val shippedSkills = listOf("web-search", "weather", "calendar", "photo-edit")

/** 随包发的样例快捷指令: 文件名去掉 `.md` 就是它在设置页里的名字 */
val shippedQuickCommands = listOf("制定旅游计划", "今天要做什么")


// 语音转写要的那套 sherpa-onnx 不在 Maven 上: 上游只把它发成 GitHub Release 里的 AAR, 所以这
// 一个依赖由构建自己取。app/libs/sherpa-onnx.aar 放了本地文件就用那一份 (离线构建、或用自己
// 编的版本), 否则按下面的版本与 sha256 下载到 build/ 里, 校验过才算数
// 取 static-link-onnxruntime 那一版是有原因的: 本应用已经带了 onnxruntime-android-qnn (端侧
// OCR 用), 普通版 AAR 会再带一份同名的 libonnxruntime.so, 打成 APK 时会撞名
val sherpaVersion = "1.13.8"
val sherpaAar = layout.buildDirectory.file("sherpa-onnx/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val sherpaAarLocal = rootProject.file("app/libs/sherpa-onnx.aar")
val sherpaAarSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"

// 短名要 import 才用得了: 脚本里 `java` 会被解析成 Gradle 那个 `java` 扩展, 所以 `java.security.*`
// 与 `java.net.*` 在这里是"在扩展上找成员", 编译期直接报 Unresolved reference
// 配置缓存的安全写法: 动作里**不许**碰脚本级的东西 (上面那个 `sha256Of` 是脚本类的成员函数,
// 而 `logger` 是脚本属性) —— 引用了就等于把整个脚本对象塞进任务, 配置缓存会以
// "cannot serialize Gradle script object references" 拒绝, 构建在 :app:compileDebugKotlin
// **成功之后**才失败 (看着像编译错, 其实不是)。所以取哈希与打印都在动作内部就地写
val fetchSherpaOnnxAar = tasks.register("fetchSherpaOnnxAar") {
    group = "luwi"
    description = "Fetch the sherpa-onnx AAR the app links for on-device speech recognition"
    val target = sherpaAar.get().asFile
    val expected = sherpaAarSha256
    val source = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/" +
        "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
    outputs.file(target)
    doLast {
        fun digestOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && digestOf(target) == expected) return@doLast
        target.parentFile.mkdirs()
        println("fetching $source")
        URI(source).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = digestOf(target)
        if (actual != expected) {
            target.delete()
            throw GradleException("the sherpa-onnx AAR is not the pinned one: sha256 $actual")
        }
    }
}

/** The submodule files whose change must invalidate the packed tree */
val dshSources = fileTree(dshCheckout.asFile) {
    include(
        "packages/*/*/src/**",
        "packages/*/*/package.json",
        "apps/*/src/**",
        "apps/*/package.json",
        "vendor/*/src/**",
        "vendor/*/package.json",
        "native/system/packages/*/src/**",
        "native/system/packages/*/package.json",
    )
}

/** Commit the tree is built from, which the pack step refuses to violate */
val dshPin: Provider<String> = providers.exec {
    commandLine("git", "-C", dshCheckout.asFile.absolutePath, "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val packHostTree = tasks.register<Exec>("packHostTree") {
    group = "luwi"
    description = "Rebuild dsh at the pinned commit and install the flat host tree the APK ships"
    workingDir = rootProject.projectDir
    // `--install` 是必须的: 子模块的 node_modules 里只有 workspace 链接, 各包自己的依赖没装, 而
    // dsh root 的 `pnpm run check` 要 tsc / tsdown —— 少了它构建会死在"某个包缺 typescript"上。
    // pnpm 那一份由 tools/pack-host.mjs 自己找 (并把 npm_execpath 补齐, 见那个文件里的长注释)
    commandLine(
        "node",
        "tools/pack-host.mjs",
        "--dsh", dshCheckout.asFile.absolutePath,
        "--out", hostTree.get().asFile.absolutePath,
        "--install",
    )
    inputs.file(rootProject.file("tools/pack-host.mjs"))
    // The plugin is copied into the tree rather than built, so its sources are an input too
    inputs.dir(rootProject.file("host-plugin"))
    // Luwi 加 (G1-2): 随包的第三方 host 插件也在 `host-plugins/` 里被拷进树 (luwi-dsh-sync 那一份),
    // 少了这一行改插件的源码不会让这个任务失效 —— 出包时会拿到上游那一份
    inputs.dir(rootProject.file("host-plugins"))
    // The image backend is copied over sharp the same way, so it is one as well
    inputs.dir(rootProject.file("image-backend"))
    // 三份预设声明与内置的 dsh-custom-mode 也从仓库拷进树 (见 tools/pack-host.mjs 那两段),
    // 所以 preset 那几份源码也是这一步的输入
    inputs.dir(rootProject.file("presets"))
    inputs.property("dshPin", dshPin)
    inputs.files(dshSources)
    outputs.dir(hostTree)
}

val zipHostTree = tasks.register<Exec>("zipHostTree") {
    group = "luwi"
    description = "Archive the host tree and stamp its version for the app to compare"
    workingDir = rootProject.projectDir
    commandLine(
        "node",
        "tools/zip-host.mjs",
        "--tree", hostTree.get().asFile.absolutePath,
        "--out", hostAssets.get().asFile.absolutePath,
        "--dsh", dshCheckout.asFile.absolutePath,
    )
    dependsOn(packHostTree)
    inputs.file(rootProject.file("tools/zip-host.mjs"))
    inputs.dir(hostTree)
    outputs.dir(hostAssets)
}

/**
 * 把随包的技能与样例快捷指令拷成 assets 的形状
 *
 * 用 `Copy` 而不是让 app 直接读仓库的那两处: APK 里只有 assets, 而源必须是一份 (仓库那份),
 * 所以这里拷一次, 而不是在仓库里再放一份副本等着它漂开
 */
val copySeedAssets = tasks.register<Copy>("copySeedAssets") {
    group = "luwi"
    description = "Copy the shipped skills and sample quick commands into the apk's assets"
    val skills = rootProject.layout.projectDirectory.dir("skills")
    val commands = rootProject.layout.projectDirectory.dir("quick-commands")
    shippedSkills.forEach { name ->
        from(skills.file("$name/SKILL.md")) { into("skills/$name") }
    }
    shippedQuickCommands.forEach { name ->
        from(commands.file("$name.md")) { into("quick-commands") }
    }
    // 探索那张任务卡的初始版 (2.7.8 批次 8): 源在仓库根, 落到 assets 根上, 由 ExploreTask 首启抄进
    // `$DSH_HOME/lw/explore-card.md` —— 之后它会随每次探索被改写, 所以这里只是一份种子
    from(rootProject.layout.projectDirectory.file("explore-card.md"))
    // 应用对应的那一份技能目录 (2.7.0 那一批): 整目录抄, 条数由 catalog.json 自己说了算 —— 加一条
    // 只写两个文件 (catalog.json 一行 + 一个 SKILL.md), 不用回来动构建脚本
    from(rootProject.layout.projectDirectory.dir("app-skills")) { into("app-skills") }
    into(seedAssets)
}

android {
    namespace = "io.github.yuloong07star.luwi"
    compileSdk {
        version = release(37)
    }

    /**
     * 签名: **固定的那一把 debug keystore**
     *
     * 为什么写死而不是让工具链自己找 (2026-10-06 踩的): 默认那条路是 `${user.home}/.android/debug.keystore`,
     * 而 `user.home` 跟着**谁在跑 gradle** 走 —— 这台机器上 Android 的家目录是 `D:\apk\.android`
     * (AVD / cache / studio 都在那儿, 10-03 装的工具链建的), 真机上装的那一版就是用它的
     * `debug.keystore` (`65:20:84:A9…`) 签的; 而在 `C:\Users\30216` 下跑一次 gradle 会**当场新建
     * 一把新的** (`83:37:F1:E8…`), 签出来的包与真机上那版**签名不同, 覆盖安装必失败** —— 那条路
     * 只剩"卸载重装", 而卸载会删掉 files/dsh-home (会话 / 凭据 / 设置)
     *
     * 所以这里点名 `D:\apk\.android\debug.keystore`: 谁跑、在哪个用户下跑, 签出来的都是同一把。
     * 换机器时用 `LW_DEBUG_KEYSTORE` 指过去 (它必须仍是**同一把旧 key**, 否则真机装不上)
     */
    val debugKeystore = providers.environmentVariable("LW_DEBUG_KEYSTORE")
        .orElse(providers.environmentVariable("ANDROID_SDK_HOME").map { "$it/.android/debug.keystore" })
        .getOrElse(rootProject.file("../.android/debug.keystore").absolutePath)
    signingConfigs {
        getByName("debug") {
            storeFile = file(debugKeystore)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "io.github.yuloong07star.luwi"
        // 33 used to be forced by miuix-blur; the blur went away on 2026-09-22, so nothing in the
        // dependency graph demands it any more and this is now a choice. Lowering it is a separate
        // decision: the accessibility tree path needs API 30, and nothing here has been run below 33.
        minSdk = 33
        targetSdk = 37
        // 2.6.5: 唤醒那一摊的整批修 (内置 custom 预设随包安装 + 语音开新会话回退 / 唤醒召回调参 +
        // 命中去抖 / 唤醒到开麦的延迟 / 在线引擎念回答时的半双工闸 / 球的状态优先级改成"想 > 说 > 听" /
        // 说完一句就收窗 / 自动指令的冷却由主人自己定)
        // 2.6.7: 球在念/听时的呼吸与涟漪 (说往外扩、听往回收) / 状态词改成"念 > 想/听 (谁最近变得谁上)" /
        // 同一场起了新轮就掐播报 / 新一轮出现就把"正在听"收回来
        // 2.7.4: LW 插件第二批 (伴侣 APK 那一层 —— 鲸鱼娘桌面小组件、从链接装入) 与设置页新加的
        // 「技能」那一段 (按已装应用把技能装进 dsh 家), 以及全仓图标换成 Luwi 自己的标记
        // (球上那圈标记 0.378 → 0.65)。这一批在 git 里的开发者标签是 v2.7.0…v2.7.3, 收口时定名 2.7.4
        // 2.7.7: 浮标外观能放人物立绘了 —— `theme/ball.json` 收 `fit` (cover/contain), contain 那一档
        // 不裁圆; 球尺寸 48 → 72 dp; 涟漪窗跟着 96 → 132 dp; peeked/失败/完成 三档都能配素材
        versionCode = 16
        versionName = "2.7.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndkVersion = "29.0.14206865"
        ndk {
            // Everything native in this app is arm64 only, the shipped runtime included, so
            // building the launcher for anything else would only add an ABI nothing can use
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        // 1.2.0: 发的就是 debug 包 (同一个 debug keystore, 才能覆盖安装、会话与记忆不丢), 而 R8 与
        // 资源裁剪对哪个 build type 都适用 —— 所以把优化开在 debug 上, 不必改发 release 包。
        // 关掉优化时 dex 是 69.6 MiB (十一个 classes*.dex), 那是这一版最大的一笔
        debug {
            optimization {
                enable = true
            }
            // 默认只有 release 才吃 proguard-rules.pro, 而这一版**发的是 debug 包**, 所以显式挂上。
            // 那份规则里最要紧的一条是特权进程的入口类名 (见文件里的注释)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    buildFeatures {
        compose = true
    }
    // The privileged launcher is the one native piece this app builds itself: it is a cmake
    // executable named liblauncher.so, because Android only executes what sits in nativeLibraryDir
    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            // Node ships as libnode.so in jniLibs and is exec'd from
            // nativeLibraryDir. That requires install-time extraction, otherwise
            // the libs stay compressed in the APK with nothing to exec.
            // QNN needs the same treatment for a different reason: fastrpc loads
            // libQnnHtpV73Skel.so by path, and ADSP_LIBRARY_PATH points at
            // nativeLibraryDir, so it has to be a real file
            useLegacyPackaging = true
            // qnn-runtime ships skels and stubs for six Hexagon generations; this device is v73, so
            // the other five are dead weight. libQnnHtpPrepare.so is the graph compiler and has to
            // stay for now because the `jit` path compiles the graph on the device
            excludes += listOf(
                "**/libQnnHtpV68*.so",
                "**/libQnnHtpV69*.so",
                "**/libQnnHtpV75*.so",
                "**/libQnnHtpV79*.so",
                "**/libQnnHtpV81*.so",
                // GPU / classic-DSP backends are another 11 MB and OCR only ever asks for HTP
                "**/libQnnGpu*.so",
                "**/libQnnDsp*.so",
            )
        }
    }
    androidResources {
        // The host archive is already deflated, so compressing it again buys nothing and the
        // app reads it as a plain stored asset
        noCompress += "zip"
    }
    // AGP 9 refuses Provider-backed source directories in the classic SourceSet API, so the
    // generated directory is named as a plain file and the dependency on the task filling it is
    // declared below
    sourceSets["main"].assets.srcDir(hostAssets.get().asFile)
    // 随包的技能与样例快捷指令同理: 源在仓库里, 进包的形状由 copySeedAssets 拼
    sourceSets["main"].assets.srcDir(seedAssets.get().asFile)
}

// Every variant's asset merge has to wait for the archive. `tasks.named` fails the build if AGP
// ever renames that task, which is the failure a missing dependency would otherwise hide
// Every variant's asset merge has to wait for the archive. AGP registers the merge tasks after
// onVariants runs, so this matches them as they appear rather than naming one now; the app
// reports a missing or stale archive on its own, and `assembleDebug --dry-run` shows
// `merge<variant>Assets` in the graph if this ever needs rechecking
androidComponents {
    onVariants { variant ->
        tasks.matching { it.name == "merge${variant.name.replaceFirstChar(Char::uppercaseChar)}Assets" }
            .configureEach {
                dependsOn(zipHostTree)
                dependsOn(copySeedAssets)
            }
    }
}


// 取 AAR 那一步要排在编译之前: 上面用的是普通文件依赖, 它自己带不上 builtBy 这条边, 所以
// 这里把 preBuild 连过去。本地放了 app/libs/sherpa-onnx.aar 就不需要下载了
if (!sherpaAarLocal.isFile) {
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchSherpaOnnxAar) }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    // Miuix UI: theme, icons, nav, preferences, squircle. No miuix-blur: the app has no blur.
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.squircle)

    implementation(libs.kotlinx.serialization.json)
    // LW 插件的接口那一份 (手写 Binder 的两个接口 + 常量, 批次 9): 伴侣样例与这里引的是同一个模块,
    // 于是"两侧同一份接口"不是一句口号 —— 描述符与 transaction code 只有一处定义
    implementation(project(":lwplugin-api"))
    // 「Edge 在线」那条朗读引擎: 那条接口是 WebSocket, 用它比手写握手可靠
    implementation(libs.okhttp)

    // The privileged channel: Shizuku's API and provider, plus libsu for the root shell
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)

    // 端侧 OCR: PP-OCR 的 det + rec 跑在 ONNX Runtime 上, NPU 走 QNN EP
    // 版本是本机与 B:\Git\Inferencer 一起验过的组合 (那台小米 13 上真建起过 QNN session),
    // 不要随手升: onnxruntime-android-qnn 与 qnn-runtime 的版本是配对的
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.46.0")


    // 语音转写: sherpa-onnx 的 Android AAR (见上面的 fetchSherpaOnnxAar)。它自带
    // libsherpa-onnx-jni.so, 里面静态链接了 onnxruntime, 与 OCR 那套 onnxruntime-android
    // 各走各的, 不是同一份
    implementation(files(sherpaAarLocal.takeIf { it.isFile } ?: sherpaAar.get().asFile))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
