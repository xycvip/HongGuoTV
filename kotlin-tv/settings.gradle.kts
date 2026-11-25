pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }

    // Kotlin 2.4.x 的元数据需要 R8 9.1.29 或更高版本。
    // AGP 8.11 自带的 R8 较旧，按 Android 官方方式显式替换。
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "HongguoTVNative"
include(":playback-api", ":playback-media", ":core", ":playback-js", ":app")
