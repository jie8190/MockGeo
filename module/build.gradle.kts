plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mockgeo.module"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mockgeo.module"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.3.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // LSPosed 模块不需要 R8 深度混淆：保持类名可读，便于 logcat 排障
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // libxposed API 只在编译期需要（框架在运行期提供）
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
    // 控制面协议常量与 App 共享（打进模块 APK，自包含）
    implementation(project(":core:model"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
