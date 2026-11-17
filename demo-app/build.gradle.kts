@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
}

android {
    enableKotlin = false

    namespace = "com.example.test.app.syscalldemo"

    // a typical configuration for a test app
    // with a minSdk of 21 and some native code

    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = 21
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
        resValues = true
    }

    dependencies {
        implementation(libs.androidx.annotation)
        implementation(projects.coreSyscall)
        implementation(libs.tencent.mmkv)
        implementation(libs.luckypray.dexkit)
    }

    // force Java 8
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

}
