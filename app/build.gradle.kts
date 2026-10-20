plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = appVersion
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.0")
}
