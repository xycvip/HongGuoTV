plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.quickjs"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api("io.github.dokar3:quickjs-kt:1.0.18")
}
