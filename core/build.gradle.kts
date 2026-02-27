plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.datetime)
    testFixturesImplementation(libs.kotlinx.datetime)
}
