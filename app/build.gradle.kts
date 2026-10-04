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
        versionCode = 101
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigningConfig = signingConfigs.create("release") {
        val keystorePath = System.getenv("KEYSTORE_FILE")
        val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("KEY_ALIAS")
        val keyPassword = System.getenv("KEY_PASSWORD")

        val keystoreFile = if (!keystorePath.isNullOrBlank()) file(keystorePath) else null

        if (keystoreFile != null && keystoreFile.exists() && keystoreFile.length() > 0L &&
            !keystorePassword.isNullOrBlank() && !keyAlias.isNullOrBlank()
        ) {
            storeFile = keystoreFile
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = if (!keyPassword.isNullOrBlank()) keyPassword else keystorePassword
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
