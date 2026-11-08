plugins {
    id("com.android.application")
}

android {
    namespace = "com.ino.driveclaude"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ino.driveclaude"
        minSdk = 31
        targetSdk = 34
        // CIの実行番号＝リリース名 build-N と一致させる（更新の判定に使う）
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "9").toInt()
        versionName = "1.9.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("fixed.keystore")
            storePassword = "driveclaude"
            keyAlias = "driveclaude"
            keyPassword = "driveclaude"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
