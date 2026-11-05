import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from an untracked keystore.properties (storeFile, storePassword, keyAlias,
// keyPassword). A missing file means an unsigned release; a broken file is recorded and fails
// checkReleaseSigning instead of failing every build, so debug builds keep working.
var releaseSigningError: String? = null
val releaseSigningProps = rootProject.file("keystore.properties")
val releaseSigningConfig = if (!releaseSigningProps.exists()) null else {
    val props = Properties().apply { releaseSigningProps.inputStream().use { load(it) } }
    fun prop(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").firstOrNull { prop(it) == null }
    if (missing != null) {
        releaseSigningError = "keystore.properties is missing $missing"
        null
    } else {
        val rawPath = prop("storeFile")!!
        val storeFileResolved = if (File(rawPath).isAbsolute) File(rawPath) else rootProject.file(rawPath)
        if (!storeFileResolved.exists()) {
            releaseSigningError = "keystore.properties: storeFile $rawPath not found"
            null
        } else {
            android.signingConfigs.create("release") {
                storeFile = storeFileResolved
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        }
    }
}

val checkReleaseSigning by tasks.registering {
    val error = releaseSigningError
    doLast { if (error != null) throw GradleException(error) }
}
// preReleaseBuild is created by AGP after evaluation, so it has to be matched lazily.
tasks.matching { it.name == "preReleaseBuild" || it.name == "preWatchtestBuild" }.configureEach { dependsOn(checkReleaseSigning) }

android {
    namespace = "com.austinlinux.pulsemetronome.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.austinlinux.pulsemetronome"
        minSdk = 30
        targetSdk = 36
        // Watch builds sort above the phone's for the same release, so both halves can share one version track.
        versionCode = (project.property("pm.versionCode") as String).toInt() + 1_000_000
        versionName = project.property("pm.versionName") as String
        manifestPlaceholders["appLabel"] = "Pulse Metronome"
        buildConfigField("boolean", "DEBUG_TOOLS", "false")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Pulse Metronome (dev)"
            buildConfigField("boolean", "DEBUG_TOOLS", "true")
        }
        release {
            // Signed only when keystore.properties is complete; never falls back to the debug key.
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
        }
        create("watchtest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".watchtest"
            manifestPlaceholders["appLabel"] = "PM watch test"
            matchingFallbacks += "release"
            buildConfigField("boolean", "DEBUG_TOOLS", "true")
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":wearlink"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.health:health-services-client:1.1.0")
    // Health Services' ExerciseClient returns ListenableFuture, which is not on the runtime classpath without Guava.
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
