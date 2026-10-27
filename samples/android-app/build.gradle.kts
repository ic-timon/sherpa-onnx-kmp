import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.k2fsa.sherpa.onnx.kmp.sample.android"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "com.k2fsa.sherpa.onnx.kmp.sample"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":sherpa-onnx-kmp"))
    implementation(libs.kotlinx.coroutines.core)
}
