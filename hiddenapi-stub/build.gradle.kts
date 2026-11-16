@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.library)
}

android {
    enableKotlin = false

    namespace = "dev.tmpfs.libcoresyscall.stub"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
        resValues = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
