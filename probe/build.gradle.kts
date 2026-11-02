plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.mockgeo.probe"
    compileSdk = 36
    defaultConfig {
        // A separate package can isolate client-scope hooks during public API checks.
        applicationId = providers.gradleProperty("probeAppId").getOrElse("com.mockgeo.probe")
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
