plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// The legal text lives only in docs/legal (also published via GitHub Pages); it is copied into
// the app's assets at build time so the in-app copy can never drift from the published one.
val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")
val copyLegalDocs by tasks.registering(Sync::class) {
    from(rootProject.file("docs/legal")) { include("*.md") }
    into(legalAssetsDir.map { it.dir("legal") })
    doLast {
        val out = legalAssetsDir.get().dir("legal").asFile
        listOf("first-launch.md", "faq.md", "privacy.md", "info.md").forEach {
            if (!File(out, it).exists()) throw GradleException("Legal document $it is missing: expected docs/legal/$it")
        }
    }
}

tasks.withType<Test>().configureEach { inputs.dir(rootProject.file("docs/legal")).withPropertyName("legalDocs") }

android {
    namespace = "com.austinlinux.pulsemetronome"
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileSdk = 36

    defaultConfig {
        applicationId = "com.austinlinux.pulsemetronome"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("main").assets.srcDir(legalAssetsDir.get().asFile)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Room 2.8's room-testing (migration tests) needs kotlinx-serialization 1.8; consistent resolution
    // would otherwise pin the test APK to the 1.6.3 that navigation-compose brings, and MigrationTest crashes.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
}

tasks.named("preBuild") { dependsOn(copyLegalDocs) }
