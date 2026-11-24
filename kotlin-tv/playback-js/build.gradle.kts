// SPDX-License-Identifier: GPL-3.0-only
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.js"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    // TV 真机只有 arm 架构；模拟器 x86 由开发机按需启用，默认裁剪减小包体。
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // quickjs-kt-jvm 只有 JNI 桥接类，原生引擎需本地编译：
                // scripts/build-quickjs-jvm-lib.sh -> libs/native/linux-x64/libquickjs.so
                it.systemProperty(
                    "com.dokar.quickjs.library.path",
                    rootProject.file("libs/native/linux-x64").absolutePath
                )
            }
        }
    }
}

dependencies {
    // quickjs-kt 1.0.18：官方 AAR 要求 compileSdk 37（AGP 9.1+），本项目 compileSdk 36，
    // 已把 aar-metadata 的 minCompileSdk 降为 36（其余内容原样），走 files() 依赖并手动
    // 补齐其传递依赖。脚本：scripts/patch-quickjs-aar.sh
    implementation(files(rootProject.file("libs/quickjs-kt-android-1.0.18.aar")))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":playback-api"))
    implementation(project(":playback-media"))
    // 演员榜门面（HongguoJsActorRank）复用 core 的 ActorRankPage / ActorWorksPage / Series 模型；
    // core 是纯 JVM 模块，Android library 依赖它没有方向问题。
    implementation(project(":core"))

    // 桌面 JVM 无法加载 Android 原生库，本地单测走 -jvm artifact 跑同一份 Kotlin 代码。
    testImplementation("io.github.dokar3:quickjs-kt-jvm:1.0.18")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // android.jar 里的 org.json 是抛异常的 stub，本地单测换成真实实现（设备上仍用框架版）。
    testImplementation("org.json:json:20240303")
}
