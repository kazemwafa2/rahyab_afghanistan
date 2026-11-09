plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksFile: String? = System.getenv("KEYSTORE_FILE")

// نشانی مخزن — یک بار در gradle.properties تغییر دهید (rahyabGithubUser=نام_کاربری_شما)
val ghUser: String = (project.findProperty("rahyabGithubUser") as String? ?: "kazemwafa2")

android {
    namespace = "af.rahyab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "af.rahyab.app"
        minSdk = 21          // Android 5.0+
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"

        // نشانی مخزن در BuildConfig قرار می‌گیرد تا User-Agent و نشانی manifest
        // بسته‌ها به‌صورت متمرکز ساخته شوند (Config.kt).
        buildConfigField("String", "RAHYAB_REPO_URL", "\"https://github.com/$ghUser/rahyab-afghanistan\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        // آزمون‌های واحد از android.util.Log و… استفاده می‌کنند (مقدار پیش‌فرض برگردانده شود)
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    // PMTiles از نسخه ۱۱٫۷ پشتیبانی می‌شود (نقشه آفلاین تک‌فایلی)
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    testImplementation("junit:junit:4.13.2")
}
