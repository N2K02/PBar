plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = providers.gradleProperty("appVersion").orElse("1.0").get()
val versionParts = appVersion.split(".")
val versionCodeValue = (versionParts.getOrNull(0)?.toIntOrNull() ?: 1) * 10 + (versionParts.getOrNull(1)?.toIntOrNull() ?: 0)

android {
    namespace = "com.n2k02.pbar"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.n2k02.pbar"
        minSdk = 26
        targetSdk = 37
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: versionCodeValue
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
    }

    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        debug { versionNameSuffix = "-debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.0")
}

dependencies {
    implementation("dev.n2k02:theme:0.1.4")
        implementation("dev.n2k02:updater:0.1.19")
    testImplementation("junit:junit:4.13.2")
}

 
// Shared updater compatibility contract: every Android build must use a pinned
// dev.n2k02:updater version >= 0.1.19. This task is also available standalone.
val verifySharedUpdaterVersion = tasks.register("verifySharedUpdaterVersion") {
    group = "verification"
    description = "Reject missing, dynamic, or outdated shared Android updater dependencies"
    doLast {
        val updaterDependencies = configurations
            .filter { it.name == "implementation" || it.name == "api" || it.name.endsWith("Implementation") }
            .flatMap { it.dependencies }
            .filter { it.group == "dev.n2k02" && it.name == "updater" }
        check(updaterDependencies.isNotEmpty()) {
            "Missing dev.n2k02:updater dependency. Require a pinned version >= 0.1.19."
        }
        updaterDependencies.forEach { dependency ->
            val version = dependency.version ?: error("dev.n2k02:updater must have a pinned version >= 0.1.19")
            val segments = version.split('.').map { it.toIntOrNull() }
            check(segments.size >= 3 && segments.all { it != null }) {
                "dev.n2k02:updater:$version is not a pinned numeric release version."
            }
            val different = segments.filterNotNull().zip(listOf(0, 1, 19))
                .firstOrNull { (actual, minimum) -> actual != minimum }
            check(different == null || different.first > different.second) {
                "dev.n2k02:updater:$version is outdated; require >= 0.1.19."
            }
        }
    }
}
// preBuild precedes both release and debug APK builds; check covers verification.
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn(verifySharedUpdaterVersion)
}
