plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.realzza.biliaccelerator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.realzza.biliaccelerator"
        minSdk = 26
        targetSdk = 34
        versionCode = 102
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val defaultSigningKeystore = file("signing/release.jks")

    val releaseSigningConfig = signingConfigs.create("release") {
        val envKeystorePath = System.getenv("KEYSTORE_FILE")
        val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
        val envKeyAlias = System.getenv("KEY_ALIAS")
        val envKeyPassword = System.getenv("KEY_PASSWORD")

        val customKeystore = if (!envKeystorePath.isNullOrBlank()) file(envKeystorePath) else null

        if (customKeystore != null && customKeystore.exists() && customKeystore.length() > 0L &&
            !envKeystorePassword.isNullOrBlank() && !envKeyAlias.isNullOrBlank()
        ) {
            storeFile = customKeystore
            storePassword = envKeystorePassword
            keyAlias = envKeyAlias
            keyPassword = if (!envKeyPassword.isNullOrBlank()) envKeyPassword else envKeystorePassword
        } else if (defaultSigningKeystore.exists() && defaultSigningKeystore.length() > 0L) {
            storeFile = defaultSigningKeystore
            storePassword = "biliaccelerator"
            keyAlias = "biliaccelerator"
            keyPassword = "biliaccelerator"
        }
    }

    signingConfigs.getByName("debug") {
        if (defaultSigningKeystore.exists() && defaultSigningKeystore.length() > 0L) {
            storeFile = defaultSigningKeystore
            storePassword = "biliaccelerator"
            keyAlias = "biliaccelerator"
            keyPassword = "biliaccelerator"
        }
    }

    val isReleaseSigningReady = releaseSigningConfig.storeFile != null &&
            releaseSigningConfig.storeFile!!.exists() &&
            releaseSigningConfig.storeFile!!.length() > 0L &&
            !releaseSigningConfig.storePassword.isNullOrBlank() &&
            !releaseSigningConfig.keyAlias.isNullOrBlank()

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (isReleaseSigningReady) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
