import java.util.Properties
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
val appVersionProperties = Properties().apply { val config = rootProject.file("version.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

// 侧载 TV 应用只有 arm 设备；libquickjs.so 的 x86/x86_64 副本（约 1.8MB）不打进包。
// 开发机要用 x86 模拟器时：HONGGUOTV_ABIS=armeabi-v7a,arm64-v8a,x86,x86_64 ./gradlew assembleDebug
val targetAbis: List<String> = (System.getenv("HONGGUOTV_ABIS") ?: "armeabi-v7a,arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = System.getenv("HONGGUOTV_APPLICATION_ID") ?: "com.x.hongguotv"
        manifestPlaceholders["appLabel"] = System.getenv("HONGGUOTV_APP_LABEL") ?: "红果TV"
        minSdk = 26
        // targetSdk 34：兼容 Play 2024 起的最低要求。本项目没有 Service/通知，
        // 主要适配点是 Scoped Storage（备份目录已迁到应用专属存储）与导出组件声明。
        targetSdk = 34
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 20
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: appVersionProperties.getProperty("APP_VERSION", "1.1.8")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 过滤 AAR 里携带的原生库（实测 0.0.101 发布包把 x86/x86_64 的 libquickjs.so 也打了进去）
        ndk { abiFilters += targetAbis }
    }
    buildFeatures { buildConfig = true; compose = true }
    signingConfigs {
        create("delivery") {
            storeType = "PKCS12"
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    // 侧载 TV 应用；targetSdk 与 Play 商店要求保持一致。
    lint { disable += "ExpiredTargetSdkVersion" }
    // bcprov 只用到 SM3Digest，PQC/X509 的附属资源文件（约 1.2MB）用不到，打包时排除
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "org/bouncycastle/pqc/**", "org/bouncycastle/x509/**") }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                it.systemProperty("robolectric.dependency.repo.id", "aliyun")
                it.systemProperty("robolectric.logging.enabled", "true")
                it.maxHeapSize = "3g"
            }
        }
    }

    // 保持 Android Gradle Plugin 默认产物名，兼容正式版与测试版发布工作流：
    // app/build/outputs/apk/release/app-release.apk
    // 版本名、版本码由 APK 内部 BuildConfig 和发布清单记录，不依赖文件名。
}
dependencies {
    // Use Android's built-in emoji font; do not bundle Emoji2's large font assets.
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3")

    // Jetpack Compose 全新界面层
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation(project(":playback-js"))
    implementation(project(":playback-api"))
    implementation(project(":playback-media"))
    // Media3 统一使用 1.11.1，避免 ExoPlayer / HLS / UI 模块版本不一致。
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // 本地单测：CMS 解析跑在 JVM，需要真实 org.json 实现（Android 平台 stub 未 mock）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Robolectric：JVM 上运行真实 Android 框架 + Compose 渲染，用于界面截图与布局校验。
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}