plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.cyclonedx)
}

allprojects {
    group = "net.bobinski.stockanalyst"
    version = "development"

    apply(plugin = "dev.detekt")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.from(rootProject.files("detekt.yml"))
    }

    tasks.withType<org.cyclonedx.gradle.BaseCyclonedxTask>().configureEach {
        componentGroup.set("net.bobinski.stockanalyst")
        componentVersion.set("development")
        includeBomSerialNumber.set(false)
        includeBuildSystem.set(false)
        externalReferences.set(
            listOf(
                org.cyclonedx.model.ExternalReference().apply {
                    type = org.cyclonedx.model.ExternalReference.Type.VCS
                    url = "https://github.com/krbob/stock-analyst"
                },
            ),
        )
        xmlOutput.unsetConvention()
    }

    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath"))
    }
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.logback.classic)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.kotlinx.datetime)
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    inputs.file(layout.projectDirectory.file("src/main/resources/openapi/stock-analyst-v1.json"))
}

tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentName.set("stock-analyst")
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/stock-analyst.cdx.json"))
    doLast {
        val sbomFile = jsonOutput.get().asFile
        val sbom = sbomFile.readText(Charsets.UTF_8)
        val timestampPattern = Regex(""""timestamp"\s*:\s*"[^"]+"""")
        check(timestampPattern.findAll(sbom).count() == 1) {
            "Expected exactly one CycloneDX metadata timestamp in ${sbomFile.path}."
        }
        sbomFile.writeText(
            sbom.replace(timestampPattern, """"timestamp" : "1970-01-01T00:00:00Z""""),
            Charsets.UTF_8,
        )
    }
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every resolvable configuration and writes complete dependency lock state."
    doFirst {
        check(gradle.startParameter.isWriteDependencyLocks) {
            "Run this task with --write-locks."
        }
    }
    doLast {
        allprojects
            .flatMap { it.configurations }
            .filter { it.isCanBeResolved }
            .forEach { it.resolve() }
    }
}
