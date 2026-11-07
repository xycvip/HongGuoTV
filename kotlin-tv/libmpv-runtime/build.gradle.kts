plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.x.hongguotv.libmpv"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
