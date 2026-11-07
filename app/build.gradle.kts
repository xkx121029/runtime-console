plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.runtimeconsole.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.runtimeconsole.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-m1"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // 必须让安装器把 lib/<abi>/*.so 解压到 nativeLibraryDir 并赋予执行权限：
            // AGP 默认 useLegacyPackaging=false（库留在 APK 内直接 mmap），那样就没有可 exec 的文件。
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime-host"))
    implementation(project(":gateway"))
    implementation(project(":bridges"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
