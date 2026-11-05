plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

// The version code is derived from pm.versionName so the ordering can't drift from the name:
// every 1.0.x (main) sorts below every 1.1.x (this line of work), whatever gets released first.
val pmVersionName = providers.gradleProperty("pm.versionName").get()
val pmVersionCode = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(pmVersionName)
    ?.destructured?.let { (major, minor, patch) ->
        if (minor.toInt() > 99 || patch.toInt() > 99) null else major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }
    ?: throw GradleException("pm.versionName must be X.Y.Z with Y and Z at most 99, got '$pmVersionName'")
extra["pmVersionName"] = pmVersionName
extra["pmVersionCode"] = pmVersionCode
