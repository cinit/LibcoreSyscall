@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.library)
}

android {
    enableKotlin = false

    namespace = "dev.tmpfs.libcoresyscall.core"

    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = 21

        consumerProguardFiles("proguard-rules.pro")
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
        resValues = false
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
        sourceCompatibility = JavaVersion.VERSION_1_8
    }

    dependencies {
        compileOnly(projects.hiddenapiStub)
        compileOnly(libs.androidx.annotation)
    }
}
