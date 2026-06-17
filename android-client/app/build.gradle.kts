plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bluelink.transfer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bluelink.transfer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // P0-8: 从环境变量或 local.properties 读取签名密钥，避免硬编码
            val storeFilePath = System.getenv("BLUELINK_STORE_FILE") ?: "release.jks"
            val storePwd = System.getenv("BLUELINK_STORE_PASSWORD") ?: ""
            val alias = System.getenv("BLUELINK_KEY_ALIAS") ?: "bluelink"
            val keyPwd = System.getenv("BLUELINK_KEY_PASSWORD") ?: ""

            storeFile = file(storeFilePath)
            if (storePwd.isNotEmpty()) storePassword = storePwd
            keyAlias = alias
            if (keyPwd.isNotEmpty()) keyPassword = keyPwd
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
}
