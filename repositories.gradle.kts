repositories {
    google()
    // Kotlin's large compiler artifacts can be redirected to GitHub Releases by the primary Maven endpoint;
    // keep a Central mirror first so CI can fetch them without depending on GitHub Release asset delivery.
    maven {
        url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
        content { includeGroup("org.jetbrains.kotlin") }
    }
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://jitpack.io")
}