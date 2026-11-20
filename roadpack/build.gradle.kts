import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("org.openstreetmap.pbf:osmpbf:1.5.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("roadpack.BuildKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.test { useJUnitPlatform() }

// The format, reader and router only, with the Kotlin runtime: dexed and run on the phone.
tasks.register<Jar>("benchJar") {
    archiveFileName.set("bench.jar")
    from(sourceSets.main.get().output) { include("roadpack/format/**", "roadpack/route/**", "roadpack/bench/**") }
    from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("kotlin-stdlib") }.map { zipTree(it) } }) {
        exclude("META-INF/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
