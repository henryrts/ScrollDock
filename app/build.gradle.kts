plugins {
    id("com.android.application")
}

val signingStorePath = System.getenv("SCROLLDOCK_KEYSTORE_PATH")
val signingStorePassword = System.getenv("SCROLLDOCK_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("SCROLLDOCK_KEY_ALIAS")
val signingKeyPassword = System.getenv("SCROLLDOCK_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.scrolldock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scrolldock"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigningConfig = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(signingStorePath!!)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            releaseSigningConfig?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
