plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val runtimeAssetsDir = providers.gradleProperty("runtimeAssetsDir").orNull
val runtimeJniLibsDir = providers.gradleProperty("runtimeJniLibsDir").orNull

android {
    namespace = "dev.runtimeconsole.runtimehost"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * 运行时 ABI 选择：默认用仓库里的 x86_64 产物；构建 arm64 时传
 * `-PruntimeAbi=arm64-v8a -PruntimeAssetsDir=... -PruntimeJniLibsDir=...`
 * （目录由 scripts/build-runtime-arm64.ps1 生成）。
 *
 * 必须整套替换而不是叠加：`assets/python-stdlib.zip` 里 `lib-dynload` 下的动态库
 * 是架构相关的，两套混在一起会得到一个"能装但内核起不来"的 APK。
 */
if (runtimeJniLibsDir != null) {
    android.sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(runtimeJniLibsDir))
}
if (runtimeAssetsDir != null) {
    android.sourceSets.getByName("main").assets.setSrcDirs(listOf(runtimeAssetsDir))
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit)
}

