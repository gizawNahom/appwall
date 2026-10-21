plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.appwall"
    compileSdk = 35
    // Pin to the build-tools we already installed so Gradle doesn't fetch another version.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.appwall"
        minSdk = 26 // Android 8.0
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes only from the environment (CI secrets or a local shell), never from the repo.
    // Without it, assembleRelease still builds, just unsigned.
    val releaseKeystore = System.getenv("APPWALL_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("APPWALL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("APPWALL_KEY_ALIAS")
                keyPassword = System.getenv("APPWALL_KEYSTORE_PASSWORD") // PKCS12 uses one password for both
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    // Test-only: real SQLite on the JVM, so the block log's SQL is tested without a phone.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
