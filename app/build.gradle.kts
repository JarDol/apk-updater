plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.jardol.apkupdater"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.jardol.apkupdater"
        minSdk = 21
        targetSdk = 35
        versionCode = 17
        versionName = "0.5.4-beta.3"
    }

    // Stały klucz w repo => każda kolejna wersja instaluje się jako aktualizacja.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/apkupdater.jks")
            storePassword = "apkupdater"
            keyAlias = "apkupdater"
            keyPassword = "apkupdater"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
