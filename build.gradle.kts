plugins {
    kotlin("jvm") version "1.9.23"
    application
}

group = "rinha"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Zero dependencies for now. We build everything raw.
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn", "-O")
    }
}

application {
    mainClass.set("rinha.MainKt")
}