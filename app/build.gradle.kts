plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val carteiraKeystorePath = System.getenv("CARTEIRA_KEYSTORE_PATH")
val carteiraKeystorePassword = System.getenv("CARTEIRA_KEYSTORE_PASSWORD")

android {
    namespace = "pt.carteira.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "pt.jokasman.carteira"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.2"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (!carteiraKeystorePath.isNullOrBlank() && !carteiraKeystorePassword.isNullOrBlank()) {
            create("stableDebug") {
                storeFile = file(carteiraKeystorePath)
                storePassword = carteiraKeystorePassword
                keyAlias = "carteira"
                keyPassword = carteiraKeystorePassword
                storeType = "pkcs12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!carteiraKeystorePath.isNullOrBlank() && !carteiraKeystorePassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
