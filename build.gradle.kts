import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    application
    `maven-publish`
    // Formatting and compiler warnings, checked by `check`: https://github.com/Brendan-Black/black-java
    id("black.java-standards") version "1.0.0"
    // NullAway: whatever isn't @Nullable is never null, checked at compile time.
    id("net.ltgt.errorprone") version "5.2.0"
}

// The java-standards checks download their formatter and compiler from here.
repositories {
    mavenCentral()
}

group = "com.github.Brendan-Black"
version = providers.environmentVariable("VERSION").orElse("dev").get().removePrefix("v")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

// Build-time only: nothing here reaches the published library, which has no dependencies.
dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.52.0")
    errorprone("com.uber.nullaway:nullaway:0.14.3")
}

// The sources keep their plain-javac layout: src/ and test/. test/PseudoTv.java isn't compiled: the end-to-end test
// runs it as a separate process, straight from source.
sourceSets {
    main { java.setSrcDirs(listOf("src")) }
    test { java { setSrcDirs(listOf("test")); exclude("PseudoTv.java") } }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:all")
    options.errorprone {
        enabled.set(this@configureEach.name == "compileJava") // tests pass bad values on purpose
        disableAllWarnings = true // only the checks below, and Error Prone's own errors, count
        error("NullAway")
        option("NullAway:AnnotatedPackages", "black.samsungtvclient")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

application {
    mainClass = "black.samsungtvclient.Main"
    applicationName = "tv"
}

tasks.jar {
    manifest { attributes("Main-Class" to "black.samsungtvclient.Main", "Implementation-Version" to project.version) }
    from("LICENSE")
}

// The tests are a plain main() (no test framework): unit checks, and pseudo TVs in process and as a process.
val runTests = tasks.register<JavaExec>("runTests") {
    description = "Runs test/black/samsungtvclient/AllTests."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "black.samsungtvclient.AllTests"
    workingDir = projectDir
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}
tasks.test { enabled = false }

// Against the real network, so not part of `check`: multicast depends on the machine it runs on.
tasks.register<JavaExec>("smoke") {
    description = "Runs SSDP discovery over real multicast, against PseudoTv."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "black.samsungtvclient.upnp.SsdpSmoke"
    workingDir = projectDir
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}
tasks.check { dependsOn(runTests) }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "SamsungTvClient"
            from(components["java"])
            pom {
                name = "SamsungTvClient"
                description = "A Java client for Samsung Smart TVs (Tizen) over the network."
                url = "https://github.com/Brendan-Black/SamsungTvClient"
                licenses { license { name = "MIT"; url = "https://opensource.org/licenses/MIT" } }
            }
        }
    }
}
