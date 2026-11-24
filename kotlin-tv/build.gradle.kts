plugins {
    id("com.android.application") version "8.11.0" apply false
    kotlin("android") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    // Jetpack Compose 编译器由 Kotlin 2.x 官方插件提供
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
