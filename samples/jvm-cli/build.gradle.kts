plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.k2fsa.sherpa.onnx.kmp.sample.MainKt"
}

dependencies {
    implementation(project(":sherpa-onnx-kmp"))
    implementation(libs.kotlinx.coroutines.core)
    providers.gradleProperty("sherpa.onnx.kmp.native.jar").orNull?.let {
        runtimeOnly(files(it))
    }
}
