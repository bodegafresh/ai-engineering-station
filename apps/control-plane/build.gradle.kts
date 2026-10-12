plugins { java; alias(libs.plugins.quarkus) }
dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.vertx)
    implementation(libs.quarkus.arc)
    implementation(project(":modules:domain"))
    implementation(project(":modules:orchestration"))
    implementation(project(":adapters:sqlite"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    implementation("org.apache.velocity:velocity-engine-core:2.4.1")
}
