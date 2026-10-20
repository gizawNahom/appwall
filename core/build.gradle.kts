// AppWall's pure core: decisions and calculations with no Android, so they can be tested on the
// JVM in seconds. Android's classes aren't on this module's classpath at all.
plugins {
    // No version: this comes from the Kotlin plugin jar the root build already loads for
    // kotlin.android, so it needs nothing extra from the network.
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Test-only (never in the APK); brings hamcrest-core 1.3. See CLAUDE.md for allowed dependencies.
    testImplementation("junit:junit:4.13.2")
}
