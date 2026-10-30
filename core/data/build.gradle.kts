plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mockgeo.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:route"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    api(libs.datastore.preferences)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20240303")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
