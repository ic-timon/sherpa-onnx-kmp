pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "sherpa-onnx-kmp"

include(":sherpa-onnx-kmp")
include(":samples:jvm-cli")
include(":samples:android-app")
