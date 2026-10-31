import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val privateProperties = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Publishing must never inherit a developer's personal map key from the environment or disk.
val publicBuild = providers.gradleProperty("publicBuild").map { it.toBooleanStrict() }.getOrElse(false)
val amapApiKey = if (publicBuild) "" else providers.environmentVariable("AMAP_API_KEY").orNull
    ?: privateProperties.getProperty("amap.apiKey", "")
require(amapApiKey.isEmpty() || amapApiKey.matches(Regex("[A-Za-z0-9]+"))) { "Invalid AMap API key format" }

val rootAssets = layout.buildDirectory.dir("generated/rootAssets")
val rootPayload = rootProject.layout.projectDirectory.file("rootpayload/build/outputs/apk/debug/rootpayload-debug.apk")
val rootLibrary = rootProject.layout.projectDirectory.file("rootpayload/build/root-native/jni/arm64-v8a/libmockgeo_root.so")
val rootLoader = rootProject.layout.projectDirectory.file("rootpayload/build/root-native/tools/mockgeo_loader")
val packageRootAssets by tasks.registering {
    dependsOn(":rootpayload:assembleDebug")
    inputs.files(rootPayload, rootLibrary, rootLoader)
    val licenses = rootProject.fileTree("rootpayload/src/main/cpp/vendor") {
        include("**/LICENSE", "**/LICENSE.txt", "**/LICENSE.md", "SOURCES.json")
    }
    inputs.files(licenses)
    outputs.dir(rootAssets)
    doLast {
        val output = rootAssets.get().dir("root").asFile.apply { mkdirs() }
        val artifacts = listOf("payload.apk" to rootPayload.asFile, "libmockgeo_root.so" to rootLibrary.asFile,
            "loader" to rootLoader.asFile)
        val properties = Properties().apply { setProperty("version", "0.1.0") }
        for ((name, source) in artifacts) {
            source.copyTo(output.resolve(name), overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            properties.setProperty(name, digest.joinToString("") { "%02x".format(it) })
        }
        output.resolve("manifest.properties").outputStream().use { properties.store(it, "Bundled Root payload") }
        val vendor = rootProject.file("rootpayload/src/main/cpp/vendor")
        for (source in licenses.files) {
            val destination = output.resolve("licenses").resolve(source.relativeTo(vendor))
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}

android {
    sourceSets.named("main") { assets.srcDir(files(rootAssets).builtBy(packageRootAssets)) }
    namespace = "com.mockgeo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mockgeo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.3"
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

tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true))
        dependsOn(packageRootAssets)
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
