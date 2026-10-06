plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jh_mmm.biliaccelerator"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.jh_mmm.biliaccelerator"
        minSdk = 26
        targetSdk = 34
        versionCode = 105
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val candidateKeystore = file("release.jks").takeIf { it.exists() && it.length() > 0L }
        ?: file("signing/release.jks").takeIf { it.exists() && it.length() > 0L }

    val releaseSigningConfig = signingConfigs.create("release") {
        storeFile = candidateKeystore
        storePassword = System.getenv("KEYSTORE_PASSWORD").takeIf { !it.isNullOrBlank() } ?: "biliaccelerator"
        keyAlias = System.getenv("KEY_ALIAS").takeIf { !it.isNullOrBlank() } ?: "biliaccelerator"
        val envKeyPassword = System.getenv("KEY_PASSWORD").takeIf { !it.isNullOrBlank() }
        keyPassword = envKeyPassword ?: (System.getenv("KEYSTORE_PASSWORD").takeIf { !it.isNullOrBlank() } ?: "biliaccelerator")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (candidateKeystore != null) {
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
    compileOnly("io.github.libxposed:api:101.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
