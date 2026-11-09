plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("DEBLOATER_KEYSTORE")

// Where the app looks for its own updates (the GitHub repository it was built from), and the signing key
// an update must have (the same fingerprint the setup tool checks). Empty in a local or debug-signed build:
// such a copy can't accept the project's signed releases anyway, so it doesn't try to update itself.
val updateRepo = (System.getenv("GITHUB_REPOSITORY") ?: "").also {
    require(it.isEmpty() || Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(it)) { "odd GITHUB_REPOSITORY" }
}
val updateSigner = rootProject.file("tool/trusted_signer.sha256").let {
    if (keystorePath != null && it.exists()) it.readText().trim().lowercase() else ""
}.also {
    require(it.isEmpty() || Regex("^[0-9a-f]{64}$").matches(it)) { "tool/trusted_signer.sha256 isn't a SHA-256 fingerprint" }
}

android {
    namespace = "app.honorpad8.debloater"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.honorpad8.debloater"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_SIGNER", "\"$updateSigner\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DEBLOATER_KEYSTORE_PASSWORD")
                keyAlias = "debloater"
                keyPassword = System.getenv("DEBLOATER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
