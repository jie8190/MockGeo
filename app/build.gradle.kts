import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val privateProperties = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val amapApiKey = providers.environmentVariable("AMAP_API_KEY").orNull
    ?: privateProperties.getProperty("amap.apiKey", "")
require(amapApiKey.isEmpty() || amapApiKey.matches(Regex("[A-Za-z0-9]+"))) { "Invalid AMap API key format" }

android {
    namespace = "com.mockgeo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mockgeo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
        buildConfigField("String", "AMAP_API_KEY", "\"$amapApiKey\"")
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
        testInstrumentationRunner = "com.mockgeo.app.DeviceSmokeInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 首版保持可读性；发布版再开 R8
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:route"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)

    implementation(libs.osmdroid)
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1")

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
