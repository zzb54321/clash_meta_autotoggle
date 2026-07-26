plugins {
    id("com.android.application")
}

// The published APK is named after the project only, without the version, so
// the file name in apk/ stays stable across releases.
base {
    archivesName = "clash_meta_autotoggle"
}

android {
    namespace = "com.zzb.clashautotoggle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zzb.clashautotoggle"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The APK published in this repository is signed with the standard Android
            // debug keystore so that it can be installed without any extra setup.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The app intentionally uses only framework APIs (no AndroidX / third party
// dependencies) to keep the APK small and the battery/runtime footprint minimal.
dependencies {
}
