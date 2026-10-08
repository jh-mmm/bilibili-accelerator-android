plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.jh_mmm.biliaccelerator"
    compileSdk = 34

    val appVersionCode = 106
    val appVersionName = "1.0.6"

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
    val envStorePass = System.getenv("KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("KEY_ALIAS")
    val envKeyPass = System.getenv("KEY_PASSWORD")

    val hasReleaseCredentials = candidateKeystore != null &&
        !envStorePass.isNullOrBlank() &&
        !envKeyAlias.isNullOrBlank()

    if (hasReleaseCredentials) {
        signingConfigs.create("release") {
            storeFile = candidateKeystore
            storePassword = envStorePass
            keyAlias = envKeyAlias
            keyPassword = envKeyPass.takeUnless { it.isNullOrBlank() } ?: envStorePass
        }
    } else {
        gradle.taskGraph.whenReady {
            if (hasTask(":app:assembleRelease") || hasTask(":app:bundleRelease") || hasTask("assembleRelease")) {
                throw GradleException(
                    "Release build aborted: Keystore file or environment credentials (KEYSTORE_PASSWORD, KEY_ALIAS) are missing. " +
                    "Debug key fallback in release builds is prohibited."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        ignoreWarnings = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures {
        compose = true
        buildConfig = true
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

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    testImplementation("junit:junit:4.13.2")
}
