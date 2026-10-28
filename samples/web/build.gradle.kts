import org.jetbrains.kotlin.gradle.dsl.JsModuleKind

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        browser()
        binaries.library()
        generateTypeScriptDefinitions()
        // The page that consumes this sample is an ES module
        // (`<script type="module">`), so the bundle must be one too. A
        // `library()` binary defaults to UMD, whose exports land on
        // `globalThis` and cannot be reached with
        // `import { runSample } from ...`.
        compilerOptions {
            moduleKind.set(JsModuleKind.MODULE_ES)
        }
    }

    sourceSets {
        jsMain.get().dependencies {
            implementation(project(":sherpa-onnx-kmp"))
        }
    }
}
