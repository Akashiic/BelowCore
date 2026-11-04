plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.akashiic.bellow"
version = System.getenv("VERSION") ?: "0.1.0-beta.6"
base.archivesName = "AkashicBellow-WorldEdit-6.1.9"

repositories { mavenCentral() }

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("org.ow2.asm:asm-commons:9.8")
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveClassifier = "agent"
    relocate("org.objectweb.asm", "com.akashiic.bellow.weagent.internal.asm")
    exclude("module-info.class", "META-INF/versions/**", "META-INF/maven/**")
    manifest {
        attributes(
            "Premain-Class" to "com.akashiic.bellow.weagent.AgentMain",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false",
            "Implementation-Title" to "Akashic Bellow WorldEdit 6.1.9 negative Y",
            "Implementation-Version" to project.version)
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// The tests patch the real WorldEdit jar, which is not redistributed here:
//   ../gradlew -p agent test -Pbellow.weJar=<worldedit-bukkit-6.1.9.jar> -Pbellow.serverJar=<Crucible server.jar>
tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.shadowJar)
    systemProperty("bellow.agentJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    project.findProperty("bellow.weJar")?.let { systemProperty("bellow.weJar", it) }
    project.findProperty("bellow.serverJar")?.let { systemProperty("bellow.serverJar", it) }
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
