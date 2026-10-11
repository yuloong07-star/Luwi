pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu is published on JitPack only, and it is the reference way to hold a root shell
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Luwi"
include(":app")
// LW 插件的接口那一份 (手写 Binder 的两个接口 + 常量): app 与伴侣样例都靠它,
// 对外发出去的 lwplugin-api-1.jar 就是它的 classes.jar (见 docs/LW-软件插件协议.md 第 9 节)
include(":lwplugin-api")
// 伴侣插件那一份样例 (批次 9 的 P1): 它不在 app 的打包路径上, 出包时单独 build
include(":sample-companion")
project(":sample-companion").projectDir = file("samples/companion")
// 鲸鱼娘桌面小组件那一份伴侣**已经收进 `archive/samples/whale-widget/`** (方案 v2 起桌面组件默认
// 走主 APK 的宿主槽, 那一份是旧路的实物), 因此这里不再是一个 Gradle 模块 —— 想重新出那个 APK
// 用 luwi-plugins 仓库里那份能构建的拷贝
