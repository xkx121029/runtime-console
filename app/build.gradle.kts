plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 把仓库根目录的 LICENSE 与 THIRD_PARTY_NOTICES.md 作为 assets 打进 APK。
 *
 * 目的：App 内的「开源许可」页必须能在**离线、无网络**的情况下打开并复制全文，
 * 不能依赖运行时去读仓库文件（设备上没有仓库）。两份文件是唯一的事实来源，
 * 这里只做改名复制，不生成第二份副本。
 */
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicenseAssets = tasks.register<Copy>("copyLicenseAssets") {
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { rename { "THIRD_PARTY_NOTICES.txt" } }
    into(licenseAssets.map { it.dir("licenses") })
}

android {
    namespace = "dev.runtimeconsole.app"
    compileSdk = 36

    sourceSets["main"].assets.srcDir(licenseAssets)

    defaultConfig {
        applicationId = "dev.runtimeconsole.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-m1"

        ndk {
            // ABI 由 Gradle 属性决定，默认 x86_64（仓库内已有产物）。
            // 构建 arm64 时传 -PruntimeAbi=arm64-v8a，必须与 runtime-host 的
            // -PruntimeAssetsDir / -PruntimeJniLibsDir 一起切换，否则会得到
            // "能装但内核起不来"的混合包（stdlib 里的 lib-dynload 与架构相关）。
            abiFilters += providers.gradleProperty("runtimeAbi").getOrElse("x86_64")
        }
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

// assets 目录由上面的 Copy 任务生成，构建前必须先跑，否则许可页读到空文件
tasks.named("preBuild") { dependsOn(copyLicenseAssets) }

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime-host"))
    implementation(project(":gateway"))
    implementation(project(":bridges"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
