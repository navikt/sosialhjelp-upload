plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor.plugin)
    alias(libs.plugins.kotlinx.serialization)
    id("jacoco")
    id("org.sonarqube") version "3.5.0.2730"
}

group = "no.nav.sosialhjelp"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
}

dependencies {
    // Common utilities
    implementation(libs.checker)

    // Ktor production dependencies from the bundle, excluding test-only module
    implementation(libs.bundles.ktor) {
        exclude(group = "io.ktor", module = "ktor-server-test-host")
    }
    // Micrometer Prometheus
    implementation(libs.micrometer.registry.prometheus)

    // Database drivers
    implementation(libs.postgresql)
    implementation(libs.h2database)
    implementation(libs.r2dbc.postgresql)

    // Dependency Injection and PDF generation
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.pdfbox.app)

    // Task scheduling grouped dependencies
    implementation(libs.bundles.ktor.task.scheduling)

    // Other dependencies
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)

    // Exposed grouped dependencies
    implementation(libs.bundles.exposed)

    // Test dependencies (added separately)
    testImplementation(libs.ktor.server.test.host)

    testImplementation(libs.mockk)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.bundles.junit)
    testImplementation(libs.bundles.testcontainers)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test) // Tests must run before report

    reports {
        xml.required.set(true) // Useful for CI tools like SonarQube
        html.required.set(true) // Easy to browse
    }

    // Optional: Filter out generated or unnecessary classes
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude("**/ApplicationKt.class") // e.g., exclude main Ktor entry point
                }
            },
        ),
    )
}
