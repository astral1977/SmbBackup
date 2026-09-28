import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.astral.smbbackup"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.astral.smbbackup"
        minSdk = 36
        targetSdk = 36
        // In GitHub Actions wird die Build-Nummer übergeben, damit Updates installierbar bleiben.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.${versionCode}"
    }

    signingConfigs {
        // Private Installation ohne Play Store: fester Schlüssel, damit Updates über die alte Version passen.
        // Eigener Schlüssel über Umgebungsvariablen möglich (siehe README).
        create("private") {
            // Leere Werte (Secret nicht gesetzt) wie "nicht vorhanden" behandeln.
            fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
            val keystore = env("SMBBACKUP_KEYSTORE")
            storeFile = if (keystore != null) file(keystore) else file("signing/dev.keystore")
            storePassword = env("SMBBACKUP_KEYSTORE_PASSWORD") ?: "smbbackup"
            keyAlias = env("SMBBACKUP_KEY_ALIAS") ?: "smbbackup"
            keyPassword = env("SMBBACKUP_KEY_PASSWORD") ?: storePassword
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("private")
        }
        release {
            // smbj und BouncyCastle nutzen Reflection; ohne Minify gibt es keine Überraschungen.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("private")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
