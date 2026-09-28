import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.smbj)
    testImplementation(libs.junit)
}

tasks.test {
    // Dateinamen mit Umlauten im Test korrekt anlegen.
    environment("LC_ALL", "C.UTF-8")
    // Integrationstest gegen einen echten SMB-Server, nur wenn SMBBACKUP_TEST_HOST gesetzt ist.
    listOf(
        "SMBBACKUP_TEST_HOST", "SMBBACKUP_TEST_PORT", "SMBBACKUP_TEST_SHARE",
        "SMBBACKUP_TEST_USER", "SMBBACKUP_TEST_PASSWORD",
    ).forEach { key -> System.getenv(key)?.let { environment(key, it) } }
}
