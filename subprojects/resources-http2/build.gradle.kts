plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Implementation for interacting with repositories over HTTP/2"

dependencies {
    api(project(":resources"))
    implementation(project(":base-services"))
    implementation(project(":core-api"))
    implementation(project(":core"))
    implementation(project(":model-core"))
    implementation(project(":logging"))

    implementation(libs.httpcore5)
    implementation(libs.httpcore5h2)
    implementation(libs.commonsHttpclient5)
    implementation(libs.brotli)
    implementation(libs.snappy)
    implementation(libs.slf4jApi)
    implementation(libs.jclToSlf4j)
    implementation(libs.jcifs)
    implementation(libs.guava)
    implementation(libs.commonsLang)
    implementation(libs.commonsIo)
    implementation(libs.jsoup)
    implementation(libs.zstd)

    // brotli native libraries
    implementation(libs.brotliNativeMacosX64)
    implementation(libs.brotliNativeMacosAarch64)
    implementation(libs.brotliNativeWindowsX64)
    implementation(libs.brotliNativeLinuxAarch64)
    implementation(libs.brotliNativeLinuxX64)

    testImplementation(project(":internal-integ-testing"))
    testImplementation(libs.jettyWebApp)
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":logging")))

    testFixturesImplementation(project(":base-services"))
    testFixturesImplementation(project(":logging"))
    testFixturesImplementation(project(":internal-integ-testing"))
    testFixturesImplementation(libs.slf4jApi)

    integTestDistributionRuntimeOnly(project(":distributions-core"))
}
