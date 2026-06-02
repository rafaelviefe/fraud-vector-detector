plugins {
    kotlin("jvm") version "1.9.23"
    id("org.graalvm.buildtools.native") version "0.9.28"
    application
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
        freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
    }
}

application {
    mainClass.set("rinha.ServerKt")
}

tasks.register<JavaExec>("buildIndex") {
    mainClass.set("rinha.BuildIndexKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Xms512m", "-Xmx2g", "-XX:+UseSerialGC")
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
                "--static",
                "-H:+ReportExceptionStackTraces",
                "-H:+UnlockExperimentalVMOptions"
            )
        }
    }
}