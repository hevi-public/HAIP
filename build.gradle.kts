import gg.jte.ContentType

plugins {
    val kotlinVersion = "2.4.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("gg.jte.gradle") version "3.2.4"
}

group = "com.aiforum"
version = "0.0.1-SNAPSHOT"

// Align the Kotlin stdlib/reflect managed by the Spring Boot BOM (2.3.21) to the
// plugin version (2.4.0) so there's no plugin/stdlib version skew.
extra["kotlin.version"] = "2.4.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

val jteVersion = "3.2.4"
val cucumberVersion = "7.34.3"
val flywayVersion = "12.4.0"   // matches the Spring Boot 4.1 BOM

dependencies {
    // --- web + SSR (JTE) — note the Spring Boot 4 starter ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("gg.jte:jte:$jteVersion")
    implementation("gg.jte:jte-spring-boot-starter-4:$jteVersion")
    implementation("gg.jte:jte-kotlin:$jteVersion")

    // --- persistence: spring-jdbc + SQLite + Flyway (NOT Hibernate) ---
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    // Spring Boot 4 modularised autoconfig — the starter brings flyway-core AND the spring-boot-flyway
    // autoconfiguration module (adding flyway-core alone leaves Flyway un-autoconfigured, so it never runs).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-nc-sqlite:$flywayVersion")   // real SQLite module name

    // --- kotlin ---
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")  // JSON <-> Kotlin data classes

    // --- test: tiers 0-2 + Cucumber acceptance over HTTP ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.platform:junit-platform-suite")
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-spring:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// --- JTE: generate template sources at build time, compiled together with the DTOs.
// The plugin wires compileKotlin to depend on generateJte and adds the generated
// sources to the source set, so a wrong field/param fails the build (no browser needed).
jte {
    generate()
    contentType.set(ContentType.Html)
}

// =====================================================================================
// Tiered test model (see the bdd-tiered-testing skill).
// Run order is lowest-first; a break low down ripples up, so read the lowest failing tier.
// Discovery mode flips ignoreFailures so a sea of red doesn't abort the build while scaffolding.
// =====================================================================================
val discoveryMode = (project.findProperty("discovery") == "true") ||
    (System.getenv("DISCOVERY_MODE") == "true")

// The default `test` task would run everything unfiltered and double-count against the
// tiered tasks below — disable it and route through verifyAll instead.
tasks.test { enabled = false }

// Manually-registered Test tasks (unlike the default `test`) don't inherit the test source set's
// classes/classpath — wire them explicitly or the task reports NO-SOURCE.
val testSourceSet = sourceSets.test.get()

fun registerTier(name: String, tag: String, after: String?) =
    tasks.register<Test>(name) {
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        // jupiter only + tag filter, so tier tasks never run the Cucumber suite.
        useJUnitPlatform { includeEngines("junit-jupiter"); includeTags(tag) }
        ignoreFailures = discoveryMode
        after?.let { shouldRunAfter(it) }
        testLogging { events("passed", "skipped", "failed") }
    }

registerTier("tier0", "tier0", null)
registerTier("tier1", "tier1", "tier0")
registerTier("tier2", "tier2", "tier1")

tasks.register<Test>("acceptance") {
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    // The Cucumber RunCucumberTest @Suite runs via the suite engine; selecting only that engine keeps
    // acceptance from re-running the jupiter tier tests. Tag filter (not @wip) lives in
    // src/test/resources/junit-platform.properties.
    useJUnitPlatform { includeEngines("junit-platform-suite") }
    // Tolerate zero discovered scenarios (true only before Phase B adds .feature files); once
    // features exist they run and failures show as red, which is the point.
    failOnNoDiscoveredTests = false
    ignoreFailures = discoveryMode
    shouldRunAfter("tier2")
    testLogging { events("passed", "skipped", "failed") }
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Runs all test tiers lowest-first, then acceptance."
    dependsOn("tier0", "tier1", "tier2", "acceptance")
}

tasks.named("check") { dependsOn("verifyAll") }
