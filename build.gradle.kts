plugins {
    kotlin("jvm") version "1.9.23"
    id("org.graalvm.buildtools.native") version "0.9.28"
}

group = "rinha"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = listOf("-O", "-Xopt-in=kotlin.RequiresOptIn")
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("server")
            mainClass.set("rinha.ServerKt")
            buildArgs.addAll(
                "-O3",
                "-march=x86-64-v3",
                "--gc=epsilon",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:+UnlockExperimentalVMOptions"
            )
        }
    }
}