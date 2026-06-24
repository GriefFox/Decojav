plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.guava)
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.ow2.asm:asm-util:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "org.decojav.Main"
}

tasks.test {
    useJUnitPlatform()
}

// Standalone fat JAR — bundles the app + all dependencies into one file.
// Build with:  ./gradlew :app:fatJar
// Run with:    java -jar decojav.jar <args>
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a self-contained executable JAR with all dependencies bundled."

    archiveFileName.set("decojav.jar")
    destinationDirectory.set(rootProject.projectDir)

    manifest {
        attributes["Main-Class"] = "org.decojav.Main"
    }

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })

    // TODO Phase J2: change EXCLUDE → MERGE for META-INF/services when ServiceLoader
    //   plugin discovery is added, so module registrations from bundled JARs are combined.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}