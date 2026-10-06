plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jh_mmm.biliaccelerator"
    compileSdk = 34

    val appVersionCode = 105
    val appVersionName = "1.0.5"

    defaultConfig {
        applicationId = "io.github.jh_mmm.biliaccelerator"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val candidateKeystore = file("release.jks").takeIf { it.exists() && it.length() > 0L }
        ?: file("signing/release.jks").takeIf { it.exists() && it.length() > 0L }

    val releaseSigningConfig = signingConfigs.create("release") {
        storeFile = candidateKeystore
        val envStorePass = System.getenv("KEYSTORE_PASSWORD")
        val envKeyAlias = System.getenv("KEY_ALIAS")
        val envKeyPass = System.getenv("KEY_PASSWORD")

        if (!envStorePass.isNullOrBlank()) {
            storePassword = envStorePass
            keyAlias = envKeyAlias.takeUnless { it.isNullOrBlank() } ?: "biliaccelerator"
            keyPassword = envKeyPass.takeUnless { it.isNullOrBlank() } ?: envStorePass
        } else {
            // 本地未配置环境变量时，使用默认开发密钥口令
            storePassword = "biliaccelerator"
            keyAlias = envKeyAlias.takeUnless { it.isNullOrBlank() } ?: "biliaccelerator"
            keyPassword = envKeyPass.takeUnless { it.isNullOrBlank() } ?: "biliaccelerator"
        }
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
        checkReleaseBuilds = true
        ignoreWarnings = false
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
