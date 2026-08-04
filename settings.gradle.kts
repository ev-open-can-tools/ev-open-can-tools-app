pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ev-open-can-tools-app"

// The transport/codec core is a plain Kotlin/JVM module: it builds and tests on
// any machine with just a JDK — no Android SDK required.
include(":protocol")

// The :app module needs the Android SDK. Include it only when an SDK is present
// (CI, or a dev box with Android Studio). On a plain JDK machine the build still
// configures and tests :protocol on its own.
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
} else {
    println("[settings] Android SDK not found — configuring :protocol only (skipping :app).")
}
