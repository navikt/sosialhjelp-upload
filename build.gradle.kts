import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor.plugin)
    alias(libs.plugins.kotlinx.serialization)
    id("nl.littlerobots.version-catalog-update") version "1.0.0"
    id("org.jooq.jooq-codegen-gradle") version libs.versions.jooq
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

val githubUser: String by project
val githubPassword: String by project

repositories {
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
    maven {
        url = uri("https://maven.pkg.github.com/navikt/*")
        credentials {
            username = githubUser
            password = githubPassword
        }
    }
}

dependencies {
    implementation(libs.fiks.kryptering)
    implementation(platform(libs.google.cloud.bom))
    implementation(libs.google.cloud.storage)
    implementation("com.google.cloud:google-cloud-storage")

    // Common utilities
    implementation(libs.bouncycastle)
    implementation(libs.checker)
    implementation(libs.apache.tika)

    implementation(libs.sosialhjelp.common.api)

    // Ktor production dependencies from the bundle, excluding test-only module
    implementation(libs.bundles.ktor) {
        exclude(group = "io.ktor", module = "ktor-server-test-host")
    }
    // Micrometer Prometheus
    implementation(libs.micrometer.registry.prometheus)

    // Database drivers
    implementation(libs.postgresql)

    // Dependency Injection and PDF generation
    implementation(libs.pdfbox.app)

    // Other dependencies
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jdbc.postgresql)

    implementation(libs.bundles.jooq)
    implementation("io.ktor:ktor-client-cio-jvm:3.2.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-jackson:3.2.3")

    // For build time codegen
    jooqCodegen(libs.jooq.meta)
    jooqCodegen(libs.postgresql)
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

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
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

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = "jdbc:postgresql://localhost:54322/sosialhjelp-upload"
            user = "postgres"
            password = "postgres"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                inputSchema = "public"
                includeTables = true
                includeIndexes = false
                includeUniqueKeys = false
                includeForeignKeys = false
                includePrimaryKeys = false
                includeXMLSchemaCollections = false
                excludes = "flyway_schema_history"
            }
            generate {
                isDefaultSchema = false
                isDefaultCatalog = false
            }
            target {
                packageName = "no.nav.sosialhjelp.upload.database.generated"
                directory = "src/main/kotlin"
            }
        }
    }
}

tasks {
    shadowJar {
        mergeServiceFiles()
    }
}
