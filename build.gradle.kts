buildscript {
    rootProject.ext["compose_version"] = "1.5.10"
    rootProject.ext["ktor_version"] = "2.3.8"
    rootProject.ext["room_version"] = "2.6.1"
    rootProject.ext["kotlin_version"] = "1.9.22"
    rootProject.ext["ksp_version"] = "1.9.22-1.0.17"
}

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}
