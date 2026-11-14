plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// 沿用旧 Java 版的签名（app/yg.keystore，口令 yg123456 / alias yg），
// 这样新版可以直接覆盖安装旧的 com.yg.vodtv，将来升级也能原地覆盖。
val ks = rootProject.file("app/yg.keystore")
val hasKs = ks.exists()

android {
    namespace = "com.yg.vodtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yg.vodtv"
        minSdk = 21
        targetSdk = 34
        versionCode = 41
        versionName = "2.28"
    }

    signingConfigs {
        if (hasKs) {
            create("yg") {
                storeFile = ks
                storePassword = "yg123456"
                keyAlias = "yg"
                keyPassword = "yg123456"
            }
        }
    }

    buildTypes {
        // debug 也签同一个 key，装到电视上才能 run-as 读日志
        getByName("debug") { if (hasKs) signingConfig = signingConfigs.getByName("yg") }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasKs) signingConfig = signingConfigs.getByName("yg")
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // app/src/main/assets 里除 live_jx.txt / src.txt 外，还有巡检脚本产出的
    // sources.json、live.json、py/（拼音索引）、logo/（台标），合计 5MB+。
    // 这些只给 CI 扫描和旧 Java 版用，新版不读，别打进 APK。
    androidResources {
        ignoreAssetsPattern = "py:logo:live.json:sources.json:" +
            "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
