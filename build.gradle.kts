plugins {
    `java-library`
}

group = "io.github.tm"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(platform(libs.kroxylicious.bom))
    compileOnly(libs.kroxylicious.api)
    compileOnly(libs.kafka.clients)
    compileOnly(libs.kroxylicious.kafka.message.tools)

    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.sts)
    implementation(libs.awssdk.url.connection.client)

    testImplementation(platform(libs.kroxylicious.bom))
    testImplementation(libs.kroxylicious.filter.test.support)
    testImplementation(libs.kroxylicious.integration.test.support)
    testRuntimeOnly(libs.kroxylicious.runtime)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
