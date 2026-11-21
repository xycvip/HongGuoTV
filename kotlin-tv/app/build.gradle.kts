import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val signingProperties = Properties().apply {
    val config = rootProject.file("keystore.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
val appVersionProperties = Properties().apply {
    val config = rootProject.file("version.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 37

    defaultConfig {
        applicationId = System.getenv("HONGGUOTV_APPLICATION_ID") ?: "com.x.hongguotv"
        manifestPlaceholders["appLabel"] = System.getenv("HONGGUOTV_APP_LABEL") ?: "红果TV"
        minSdk = 26
        targetSdk = 28
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 12
        versionName = System.getenv("HONGGUOTV_VERSION_NAME")
            ?: appVersionProperties.getProperty("APP_VERSION", "1.0.0")
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.create("delivery") {
                storeType = "PKCS12"
                storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
                storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(project(":quickjs-runtime"))
}
