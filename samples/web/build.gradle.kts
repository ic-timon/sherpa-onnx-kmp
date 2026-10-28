plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        browser()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":sherpa-onnx-kmp"))
            }
        }
    }
}
