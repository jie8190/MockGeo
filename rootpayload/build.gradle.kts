plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val nativeOutput = layout.buildDirectory.dir("root-native")
val buildRootNative by tasks.registering(Exec::class) {
    inputs.dir("src/main/cpp")
    inputs.file(rootProject.file("scripts/build-root-native.py"))
    outputs.dir(nativeOutput.map { it.dir("jni") })
    outputs.dir(nativeOutput.map { it.dir("tools") })
    commandLine("python", rootProject.file("scripts/build-root-native.py"),
        "--sdk", System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getenv("LOCALAPPDATA")}/Android/Sdk",
        "--output", nativeOutput.get().asFile.absolutePath)
}

android {
    namespace = "com.mockgeo.root"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mockgeo.rootpayload"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.named("main") {
        java.srcDir(rootProject.file("module/src/main/kotlin"))
        java.exclude("**/MockGeoModule.kt", "**/SystemHookBootstrap.kt", "**/StatusActivity.kt")
        jniLibs.srcDir(nativeOutput.map { it.dir("jni") })
    }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += "META-INF/{AL2.0,LGPL2.1}" }
}
tasks.named("preBuild") { dependsOn(buildRootNative) }
dependencies {
    implementation(project(":core:model"))
    implementation(files(rootProject.file("core/engine/libs/libxposed-api-102.jar")))
    compileOnly(libs.androidx.annotation)
}
