/**
 * 鲸鱼娘桌面小组件 —— companion 形态的 LW 插件 (协议 10.4: 桌面组件只能由伴侣 APK 声明)
 *
 * 三件事在这一份 APK 里: 一个 `AppWidgetProvider` (桌面上那只会动的小鲸鱼娘)、一个伴侣自带的界面
 * (三套动作的预览与切换)、一个实现 `ILwPlugin` 的 Service (让模型也能切)。
 *
 * 与 `:sample-companion` 同一条约定: 与主 APK 共用同一把 debug keystore, 于是它走"同签名伴侣"那一档
 */
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.yuloong07star.luwi.sample.whalewidget"
    compileSdk {
        version = release(37)
    }

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
        applicationId = "io.github.yuloong07star.luwi.sample.whalewidget"
        minSdk = 33
        targetSdk = 37
        versionCode = 6
        versionName = "1.1.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(project(":lwplugin-api"))
}
